#include "app/organicmaps/sdk/Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "map/framework.hpp"
#include "map/route_places.hpp"
#include "map/search_api.hpp"

#include "platform/platform.hpp"

#include <algorithm>
#include <array>
#include <cmath>

namespace
{
// Accessed only on the GUI thread. Background queries own immutable geometry and result snapshots.
jlong g_requestId = 0;
std::vector<std::weak_ptr<search::ProcessorHandle>> g_handles;
std::array<std::vector<route_places::Place>, 3> g_places;

void Cancel()
{
  ++g_requestId;
  for (auto const & handle : g_handles)
    if (auto processor = handle.lock())
      processor->Cancel();
  g_handles.clear();
  for (auto & places : g_places)
    places.clear();
}
}  // namespace

extern "C"
{
JNIEXPORT jlong JNICALL Java_app_organicmaps_sdk_routing_RoutePlace_search(JNIEnv * env, jclass, jdouble fromMeters,
                                                                           jdouble toMeters, jobject listener)
{
  Cancel();
  if (!std::isfinite(fromMeters) || !std::isfinite(toMeters) || fromMeters < 0 || toMeters <= fromMeters)
    return 0;

  auto & framework = *g_framework->NativeFramework();
  auto route = framework.GetRoutingManager().GetRoutePointsBetween(
      fromMeters, std::min(toMeters, fromMeters + route_places::kMaxAheadMeters));
  if (route.size() < 2)
    return 0;

  static jclass const placeClass = jni::GetGlobalClassRef(env, "app/organicmaps/sdk/routing/RoutePlace");
  static jmethodID const ctor = jni::GetConstructorID(env, placeClass, "(JIILjava/lang/String;DD)V");
  auto const onPlaces = jni::GetMethodID(env, listener, "onPlaces", "(JI[Lapp/organicmaps/sdk/routing/RoutePlace;)V");
  auto const requestId = g_requestId;
  g_handles = route_places::Search(framework.GetSearchAPI().GetEngine(), framework.GetDataSource(), std::move(route),
                                   [requestId, ref = jni::make_global_ref(listener), onPlaces](
                                       route_places::Category category, std::vector<route_places::Place> places)
  {
    GetPlatform().RunTask(Platform::Thread::Gui,
                          [requestId, ref, onPlaces, category, places = std::move(places)]() mutable
    {
      if (requestId != g_requestId)
        return;
      auto * env = jni::GetEnv();
      auto const cat = static_cast<jint>(category);
      g_places[cat] = std::move(places);
      auto const & stored = g_places[cat];
      jni::TScopedLocalObjectArrayRef array(
          env, env->NewObjectArray(static_cast<jsize>(stored.size()), placeClass, nullptr));
      for (size_t i = 0; i < stored.size(); ++i)
      {
        auto const & place = stored[i];
        jni::TScopedLocalRef name(env, jni::ToJavaString(env, place.m_result.GetString()));
        jni::TScopedLocalRef item(env,
                                  env->NewObject(placeClass, ctor, requestId, cat, static_cast<jint>(i), name.get(),
                                                 place.m_position.m_distanceMeters, place.m_position.m_offsetMeters));
        env->SetObjectArrayElement(array.get(), static_cast<jsize>(i), item.get());
      }
      env->CallVoidMethod(*ref, onPlaces, requestId, cat, array.get());
    });
  });
  return requestId;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_routing_RoutePlace_cancel(JNIEnv *, jclass, jlong requestId)
{
  if (requestId == g_requestId)
    Cancel();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_routing_RoutePlace_show(JNIEnv *, jclass, jlong requestId,
                                                                        jint category, jint index)
{
  if (requestId != g_requestId || category < 0 || category >= static_cast<jint>(g_places.size()) || index < 0 ||
      index >= static_cast<jint>(g_places[category].size()))
    return;
  auto & framework = *g_framework->NativeFramework();
  framework.StopLocationFollow();
  // Unlike ShowSearchResult, this preserves any active search-wheel query and its marks.
  framework.SelectSearchResult(g_places[category][index].m_result, true /* animation */);
}
}  // extern "C"
