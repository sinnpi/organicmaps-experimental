package app.organicmaps.util.bottomsheet;

public class MenuBottomSheetItem
{
  public final int titleRes;
  public final int iconRes;
  public final int badgeCount;
  public final boolean opensSubmenu;
  public final OnClickListener onClickListener;

  public MenuBottomSheetItem(int titleRes, int iconRes, OnClickListener onClickListener)
  {
    this(titleRes, iconRes, 0, false, onClickListener);
  }

  public MenuBottomSheetItem(int titleRes, int iconRes, int badgeCount, OnClickListener onClickListener)
  {
    this(titleRes, iconRes, badgeCount, false, onClickListener);
  }

  private MenuBottomSheetItem(int titleRes, int iconRes, int badgeCount, boolean opensSubmenu,
                              OnClickListener onClickListener)
  {
    this.titleRes = titleRes;
    this.iconRes = iconRes;
    this.badgeCount = badgeCount;
    this.opensSubmenu = opensSubmenu;
    this.onClickListener = onClickListener;
  }

  /**
   * An item that opens another menu sheet, marked with a chevron.
   */
  public static MenuBottomSheetItem submenu(int titleRes, int iconRes, OnClickListener onClickListener)
  {
    return new MenuBottomSheetItem(titleRes, iconRes, 0, true, onClickListener);
  }

  public interface OnClickListener
  {
    void onClick();
  }
}
