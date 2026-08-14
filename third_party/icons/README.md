# Bundled icons

## 1-bit Pixel Icons (pix_*.png)

- Source: https://nikoichu.itch.io/pixel-icons (v1.2, 1476 icons)
- Author: Nikoichu
- License: Creative Commons Zero v1.0 Universal (CC0) — per the itch.io asset page
  («Asset license: Creative Commons Zero v1.0 Universal»); attribution optional,
  provided here.
- Shipped as a curated subset (~39 icons) at `app/src/main/res/drawable-nodpi/pix_*.png`,
  16x16 px originals, rendered nearest-neighbour and runtime-tinted by the CLI theme
  (see `ui/cli/components/CliPixIcon.kt`). Original category filenames are preserved in
  the mapping below.
- Preprocessed: the pack ships two-color sprites (black line art + white fill, both
  opaque); a runtime tint would flatten them into blobs. We keep ONLY the black line-art
  pixels, re-encoded as white-on-transparent, so `ColorFilter.tint` paints the actual
  drawing. (Script: pure-python PNG filter, black = a>127 && r<128.)

| resource | pack file |
| --- | --- |
| pix_home | Map_Markers_Building_Home_House |
| pix_profiles | Travel_Person_Player_Character_Single |
| pix_apps | Boardgames_Tetris_Piece_Block |
| pix_map | Map_Markers_Scroll_Map_Location |
| pix_stats | Media_Audio_Visualizer_VU_Meters |
| pix_settings | Software_Options_Settings_Cogwheel_Gear_Mechanics |
| pix_tor | Platforms_Browsers_Tor_Onion |
| pix_shield | Boardgames_Card_Defense_Shield |
| pix_fire | Alchemy_Element_Fire |
| pix_status | Misc_Heartbeat_Line_1 |
| pix_restart | Arrows_Media_Controls_Loop_Reload_Refresh |
| pix_power | Arrows_Power_Button_Switch_Turn_On_Off |
| pix_lock | Tools_Crafting_Padlock_Locked |
| pix_incognito | Hats_Domino_Mask_Incognito_Private_Privacy |
| pix_dns | Software_Storage_Drives_Discs_Disks_Server_1 |
| pix_terminal | Software_Terminal_Window_CMD_Command_Line_Development_Code_Programming |
| pix_journal | Software_File_Document_Page_Text_Word |
| pix_globe | Software_Planet_Geography_Localization_Global_Language_Translation_1 |
| pix_theme | Tools_Crafting_Graphic_Design_Eyedropper_Color_Picker |
| pix_font | Tools_Crafting_Graphic_Design_Font_Text_Typing_A |
| pix_info | Software_Speech_Bubble_Information_Guide_Tutorial |
| pix_check | Software_Signs_Checkmark_Checkbox_Ticked_Todo |
| pix_cross | Software_Signs_Maths_Multiplication_X_Crossout_Checkmark_Cancel |
| pix_forbidden | Software_Sign_Crossout_Cancel_Forbidden_Illegal_1 |
| pix_star | Boardgames_Card_Star |
| pix_arrow_right | Arrows_Pointer_Right_East |
| pix_arrow_down | Arrows_Pointer_Down_South |
| pix_qr | Software_Barcode_Scan |
| pix_add | Software_File_Document_Page_Plus_Add_New |
| pix_import | Software_File_Document_Page_Arrow_Send_Import |
| pix_export | Software_File_Document_Page_Arrow_Send_Export |
| pix_trash | Software_Trashcan_Garbage_Bin_Rubbish_Delete_Erase_1 |
| pix_edit | Software_Pen_Paper_Notes_Edit_Text |
| pix_copy | Software_Clipbaord_List_File_Copy_Paste |
| pix_clock | Software_Clock_Time_Wait_1 |
| pix_up | Arrows_Up_North |
| pix_down | Arrows_Down_South |
| pix_link | Software_Link_Chain_Shortcut_Combo |
| pix_update | Software_Internet_Download_from_Cloud |
| pix_webapps | (hand-drawn for FHG in the pack style: browser window with app tiles; CC0) |
