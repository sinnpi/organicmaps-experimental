package app.organicmaps.maplayer;

import static org.junit.Assert.assertEquals;

import app.organicmaps.maplayer.SearchWheel.SearchOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SearchOptionSelectionTest
{
  private static final List<SearchOption> DEFAULTS =
      Arrays.asList(SearchOption.FUEL, SearchOption.PARKING, SearchOption.EAT, SearchOption.FOOD, SearchOption.ATM);

  @Test
  public void fallsBackToTheDefaultsWhenNothingCanBeShown()
  {
    assertEquals(DEFAULTS, SearchOption.parse(null));
    assertEquals(DEFAULTS, SearchOption.parse(""));
    assertEquals(DEFAULTS, SearchOption.parse("clown_hire,dragon_stabling"));
    assertEquals(DEFAULTS, SearchOption.parse("none,none"));
  }

  @Test
  public void keepsExistingSelectionsAndSlotPositions()
  {
    assertEquals(DEFAULTS, SearchOption.parse("fuel,parking,eat,food,atm"));
    assertEquals(Arrays.asList(SearchOption.WATER, SearchOption.TOILET), SearchOption.parse("water,toilet"));
    assertEquals(Arrays.asList(SearchOption.WATER, SearchOption.NONE, SearchOption.PHARMACY),
                 SearchOption.parse("water,unknown,pharmacy"));
  }

  @Test
  public void neverReturnsMoreThanTheWheelHasRoomFor()
  {
    final List<SearchOption> parsed = SearchOption.parse("water,toilet,pharmacy,hospital,hotel,shopping,bank");
    assertEquals(SearchOption.MAX_SELECTED, parsed.size());
    assertEquals(Arrays.asList(SearchOption.WATER, SearchOption.TOILET, SearchOption.PHARMACY, SearchOption.HOSPITAL,
                               SearchOption.HOTEL),
                 parsed);
  }

  @Test
  public void writesInSlotOrder()
  {
    assertEquals("water,fuel", SearchOption.format(Arrays.asList(SearchOption.WATER, SearchOption.FUEL)));
    assertEquals("", SearchOption.format(Collections.emptyList()));
  }

  @Test
  public void freeTextSearchCanOccupyAnySlot()
  {
    for (int slot = 0; slot < SearchOption.MAX_SELECTED; ++slot)
    {
      final SearchOption[] options = DEFAULTS.toArray(new SearchOption[0]);
      options[slot] = SearchOption.SEARCH;
      assertEquals(Arrays.asList(options), SearchOption.parse(SearchOption.format(Arrays.asList(options))));
    }
  }

  @Test
  public void roundTripsHiddenAndRepeatedActionsWithoutMovingThem()
  {
    final List<SearchOption> selection = Arrays.asList(SearchOption.SEARCH, SearchOption.NONE, SearchOption.WATER,
                                                       SearchOption.SEARCH, SearchOption.TOILET);
    assertEquals(selection, SearchOption.parse(SearchOption.format(selection)));
  }
}
