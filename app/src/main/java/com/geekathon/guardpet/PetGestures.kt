package com.geekathon.guardpet

enum class PetAction(val key: String, val labelRes: Int) {
    CHANGE_STYLE("change_style", R.string.action_change_style),
    FEATURE_MENU("feature_menu", R.string.action_feature_menu),
    CONTROL_PANEL("control_panel", R.string.control_panel),
    EXTRACT_TEXT("extract_text", R.string.extract_text),
    FLASH_NOTE("flash_note", R.string.flash_note_capture),
    FLASH_NOTE_LIST("flash_note_list", R.string.flash_note_list),
    FOCUS_TIMER("focus_timer", R.string.focus_timer),
    PET("pet", R.string.pet);

    companion object {
        fun fromKey(key: String?, fallback: PetAction): PetAction =
            entries.firstOrNull { it.key == key } ?: fallback
    }
}

enum class PetGesture(val key: String, val defaultAction: PetAction, val labelRes: Int) {
    SINGLE_TAP("single_tap", PetAction.CHANGE_STYLE, R.string.gesture_single_tap),
    DOUBLE_TAP("double_tap", PetAction.FEATURE_MENU, R.string.gesture_double_tap),
    SHAKE("shake", PetAction.PET, R.string.gesture_shake),
    DOUBLE_TAP_HOLD("double_tap_hold", PetAction.CONTROL_PANEL, R.string.gesture_double_tap_hold),
    /** 拖拽桌宠时摇晃手机 */
    DRAG_PHONE_SHAKE("drag_phone_shake", PetAction.EXTRACT_TEXT, R.string.gesture_drag_phone_shake);

    companion object {
        val shortcuts = entries.toList()
    }
}
