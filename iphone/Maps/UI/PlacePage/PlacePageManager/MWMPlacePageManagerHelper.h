@class PlacePageData;
@class PlacePagePhone;
@class ElevationProfileData;

@interface MWMPlacePageManagerHelper : NSObject

+ (void)updateAvailableArea:(CGRect)frame;
+ (void)editPlace;
+ (void)addBusiness;
+ (void)addPlace:(CLLocationCoordinate2D)coordinate;
+ (void)openWebsite:(PlacePageData *)data;
+ (void)openHeritageWebsite:(PlacePageData *)data;
+ (void)openWebsiteMenu:(PlacePageData *)data;
+ (void)openWikipedia:(PlacePageData *)data;
+ (void)openWikimediaCommons:(PlacePageData *)data;
+ (void)openEmail:(PlacePageData *)data;
+ (void)openFacebook:(PlacePageData *)data;
+ (void)openInstagram:(PlacePageData *)data;
+ (void)openTwitter:(PlacePageData *)data;
+ (void)openVk:(PlacePageData *)data;
+ (void)openLine:(PlacePageData *)data;
+ (void)call:(PlacePagePhone *)phone;
+ (void)showAllFacilities:(PlacePageData *)data;
+ (void)showPlaceDescription:(NSString *)htmlString;
+ (void)openMoreUrl:(PlacePageData *)data;
+ (void)openReviewUrl:(PlacePageData *)data;
+ (void)openDescriptionUrl:(PlacePageData *)data;
+ (void)openCatalogSingleItem:(PlacePageData *)data atIndex:(NSInteger)index;
+ (void)openCatalogMoreItems:(PlacePageData *)data;
+ (void)addBookmark:(PlacePageData *)data;
+ (void)updateBookmark:(PlacePageData *)data
                 title:(NSString *)title
                 color:(UIColor *)color
              category:(MWMMarkGroupID)category;
+ (void)removeBookmark:(PlacePageData *)data;
+ (void)updateTrack:(PlacePageData *)data
              title:(NSString *)title
              color:(UIColor *)color
           category:(MWMMarkGroupID)category;
+ (void)removeTrack:(PlacePageData *)data;
+ (void)editBookmark:(PlacePageData *)data;
+ (void)editTrack:(PlacePageData *)data;
+ (void)searchBookingHotels:(PlacePageData *)data;
+ (void)book:(PlacePageData *)data;
+ (void)routeFrom:(PlacePageData *)data;
+ (void)routeTo:(PlacePageData *)data;
/// @param reverse follow the track towards its start instead of its end, for when the user is
///                heading to the other end of it.
+ (BOOL)followTrack:(PlacePageData *)data reverse:(BOOL)reverse;
/// Follows the track only as far as the point the user selected on it, rather than to either end.
+ (BOOL)followTrackToSelectedPoint:(PlacePageData *)data;
+ (void)routeAddStop:(PlacePageData *)data;
+ (void)routeRemoveStop:(PlacePageData *)data;
+ (void)avoidDirty;
+ (void)avoidFerry;
+ (void)avoidToll;

@end
