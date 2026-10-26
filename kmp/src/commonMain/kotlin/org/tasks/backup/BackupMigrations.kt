package org.tasks.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tasks.caldav.canonicalUrl
import org.tasks.data.entity.Alarm
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.Task
import org.tasks.data.entity.Task.RepeatFrom
import org.tasks.themes.TasksIcons

/**
 * Version-gated migration helpers used by [LocalBackupImporter].
 *
 * The gate values and behavior mirror the Android importer
 * (`TasksJsonImporter` + the `com.todoroo.astrid.service.Upgrade_*`
 * classes) so that backups written by any supported Android version
 * import identically on desktop.
 */
object BackupVersions {
    const val V6_4 = 546
    const val V8_2 = 675
    const val V9_6 = 90600
    const val V12_4 = 120400
    const val V12_8 = 120800
    const val UPGRADE_13_2 = 130200
    const val UPGRADE_14_13 = 141300
    const val UPGRADE_15_10 = 151000
    const val UPGRADE_15_13 = 151300
}

/**
 * Maps a pre-8.2 legacy color index to the ARGB value of the kmp color
 * resource the native `Upgrader.getAndroidColor` resolves.
 * Returns [def] for unknown indices.
 */
fun legacyColor(index: Int, def: Int = 0): Int = when (index) {
    0 -> 0xFF607D8B.toInt() // blue_grey_500
    1 -> 0xFF212121.toInt() // grey_900
    2 -> 0xFFF44336.toInt() // red_500
    3 -> 0xFFE91E63.toInt() // pink_500
    4 -> 0xFF9C27B0.toInt() // purple_500
    5 -> 0xFF673AB7.toInt() // deep_purple_500
    6 -> 0xFF3F51B5.toInt() // indigo_500
    7 -> 0xFF2196F3.toInt() // blue_500
    8 -> 0xFF03A9F4.toInt() // light_blue_500
    9 -> 0xFF00BCD4.toInt() // cyan_500
    10 -> 0xFF009688.toInt() // teal_500
    11 -> 0xFF4CAF50.toInt() // green_500
    12 -> 0xFF8BC34A.toInt() // light_green_500
    13 -> 0xFFCDDC39.toInt() // lime_500
    14 -> 0xFFFFEB3B.toInt() // yellow_500
    15 -> 0xFFFFC107.toInt() // amber_500
    16 -> 0xFFFF9800.toInt() // orange_500
    17 -> 0xFFFF5722.toInt() // deep_orange_500
    18 -> 0xFF795548.toInt() // brown_500
    19 -> 0xFF9E9E9E.toInt() // grey_500
    20 -> 0xFFFFFFFF.toInt() // white_100
    else -> def
}

/**
 * Port of `Upgrade_13_11.migrateLegacyIcon`: icon values persisted as
 * numeric resource indices before 13.11 become icon names.
 */
fun String?.migrateLegacyIcon(): String? {
    val index = this?.toIntOrNull()
    return if (index == null) this else LEGACY_ICONS[index]
}

private val LEGACY_ICONS = mapOf(
    -1 to null,
    0 to null,
    1 to TasksIcons.LABEL,
    2 to TasksIcons.FILTER_LIST,
    3 to TasksIcons.CLOUD,
    4 to TasksIcons.ALL_INBOX,
    5 to TasksIcons.LABEL_OFF,
    6 to TasksIcons.HISTORY,
    7 to TasksIcons.TODAY,
    8 to TasksIcons.LIST,
    1000 to "flag",
    1001 to "pets",
    1002 to "payment",
    1003 to "attach_money",
    1004 to "hourglass_empty",
    1005 to "favorite_border",
    1006 to "school",
    1007 to "drive_eta",
    1008 to "whatshot",
    1009 to "star_border",
    1010 to "account_balance",
    1011 to "location_city",
    1012 to "cake",
    1013 to "kitchen",
    1014 to "fitness_center",
    1015 to "child_friendly",
    1016 to "free_breakfast",
    1017 to "golf_course",
    1018 to "beach_access",
    1019 to "restaurant_menu",
    1020 to "local_pharmacy",
    1021 to "fastfood",
    1022 to "hotel",
    1023 to "flight",
    1024 to "directions_run",
    1025 to "wb_sunny",
    1026 to "desktop_mac",
    1027 to "computer",
    1028 to "format_paint",
    1029 to "storage",
    1030 to "send",
    1031 to "weekend",
    1032 to "link",
    1033 to "business",
    1034 to "chat_bubble_outline",
    1035 to "voicemail",
    1036 to "email",
    1037 to "call",
    1038 to "new_releases",
    1039 to "movie",
    1040 to "equalizer",
    1041 to "work_outline",
    1042 to "store",
    1043 to "shopping_cart",
    1044 to "schedule",
    1045 to "photo_camera",
    1046 to TasksIcons.DELETE,
    1047 to "attachment",
    1048 to "vpn_key",
    1049 to "event",
    1050 to TasksIcons.PLACE,
    1051 to "markunread_mailbox",
    1052 to "label_important",
    1053 to "android",
    1054 to "build",
    1055 to "bug_report",
    1056 to "book",
    1057 to "flight_takeoff",
    1058 to "flight_land",
    1059 to "euro_symbol",
    1060 to "explore",
    1061 to "gavel",
    1062 to "home",
    1063 to "print",
    1064 to "receipt",
    1065 to "report_problem",
    1066 to "turned_in",
    1067 to "turned_in_not",
    1068 to "error_outline",
    1069 to "not_interested",
    1070 to "report",
    1071 to "pie_chart",
    1072 to "show_chart",
    1073 to "headset",
    1074 to "mic_none",
    1075 to TasksIcons.TIMER,
    1076 to TasksIcons.CLEAR,
    1077 to "search",
    1078 to "repeat",
    1079 to TasksIcons.NOTIFICATIONS,
    1080 to "star_half",
    1081 to "share",
    1082 to "sentiment_very_satisfied",
    1083 to "sentiment_very_dissatisfied",
    1084 to "sentiment_satisfied",
    1085 to "sentiment_dissatisfied",
    1086 to "mood_bad",
    1087 to "mood",
    1088 to "spa",
    1089 to "room_service",
    1090 to "meeting_room",
    1091 to "hot_tub",
    1092 to "business_center",
    1093 to "priority_high",
    1094 to "power",
    1095 to "power_off",
    1096 to "directions_bike",
    1097 to "local_florist",
    1098 to "local_pizza",
    1099 to "navigation",
    1100 to "local_play",
    1101 to "local_bar",
    1102 to "local_laundry_service",
    1103 to "local_offer",
    1104 to "local_shipping",
    1105 to "local_hospital",
    1106 to "directions_boat",
    1107 to "directions_walk",
    1108 to "wb_incandescent",
    1109 to "landscape",
    1110 to "music_note",
    1111 to "healing",
    1112 to "brush",
    1113 to "brightness_2",
    1114 to "security",
    1115 to "scanner",
    1116 to "router",
    1117 to "watch",
    1118 to "videogame_asset",
    1119 to "cached",
    1121 to "perm_identity",
    1122 to "track_changes",
    1123 to "open_in_new",
    1124 to TasksIcons.EDIT,
    1125 to "info",
    1126 to "palette",
    1127 to "sd_storage",
    1128 to "baseline_lens",
    1129 to "map",
    1130 to "check_blackdp",
    1131 to "undo",
    1132 to "next_week",
    1133 to "local_cafe",
    1134 to "nights_stay",
    1135 to "single_bed",
    1136 to "weather_sunset",
    1137 to "calendar_today",
    1138 to "select_all",
    1139 to "widgets",
    1140 to "call_split",
    1141 to "ac_unit",
    1142 to "airport_shuttle",
    1143 to "apartment",
    1144 to "bathtub",
    1145 to "casino",
    1146 to "child_care",
    1147 to "pool",
    1148 to "house",
    1149 to "storefront",
    1150 to "poll",
    1151 to "emoji_transportation",
    1152 to "emoji_objects",
    1153 to "emoji_nature",
    1154 to "emoji_food_beverage",
    1155 to "emoji_events",
    1156 to "deck",
    1157 to "fireplace",
    1158 to "outdoor_grill",
    1159 to "thumb_up",
    1160 to "thumb_down",
    1161 to "vertical_align_top",
    1162 to "keyboard_arrow_left",
    1163 to "keyboard_arrow_right",
    1164 to "content_paste",
    1165 to "content_copy",
    1166 to "play_arrow",
    1167 to "play_circle_outline",
    1168 to "not_started",
    1169 to "date_range",
    1170 to TasksIcons.PENDING_ACTIONS,
    1171 to "visibility_off",
    1172 to "nature",
    1173 to "eco",
    1174 to "bedtime",
    1175 to "auto_stories",
    1176 to "flash_on",
    1177 to "wb_twilight",
    1178 to "local_atm",
    1179 to "cleaning_services",
    1180 to "plumbing",
    1181 to "pest_control_rodent",
    1182 to "people_outline",
    1183 to "forum",
    1185 to "person_add",
    1186 to "block",
)

/** Port of `Migrations.repeatFrom` (pre-12.8 recurrence format). */
fun String?.repeatFrom(): Int =
    if (this?.contains("FROM=COMPLETION") == true) {
        RepeatFrom.COMPLETION_DATE
    } else {
        RepeatFrom.DUE_DATE
    }

/** Port of `Migrations.withoutFrom` (pre-12.8 recurrence format). */
fun String?.withoutFrom(): String? = this?.replace(";?FROM=[^;]*".toRegex(), "")

/**
 * Port of `TasksJsonImporter.reconstructDirtyVersion`: derives the
 * per-CaldavTask dirty state for backups that predate the explicit
 * dirty/synced version fields.
 */
internal fun reconstructDirtyVersion(lastSync: Long?, modified: Long): Pair<Long, Long> = when {
    lastSync == null || lastSync == 0L -> 1L to 0L
    modified > lastSync -> 2L to 1L
    else -> 1L to 1L
}

/** Port of `Upgrade_14_11.fromLegacyFlags` (pre-12.4 ring flags). */
fun legacyAlarmsFromFlags(flags: Int, task: Long = 0): List<Alarm> = buildList {
    if (flags and Task.NOTIFY_AT_START != 0) add(Alarm.whenStarted(task))
    if (flags and Task.NOTIFY_AT_DEADLINE != 0) add(Alarm.whenDue(task))
    if (flags and Task.NOTIFY_AFTER_DEADLINE != 0) add(Alarm.whenOverdue(task))
}

/**
 * CalDAV URL canonicalization for pre-15.13 backups. Same rules as the
 * app's `Upgrade_15_13.canonicalized` (jvmCommonMain, not reachable from
 * commonMain), built on the shared [canonicalUrl] helper.
 */
internal fun CaldavAccount.canonicalizedBackupUrl(): CaldavAccount =
    if (accountType == CaldavAccount.TYPE_CALDAV || accountType == CaldavAccount.TYPE_TASKS) {
        copy(url = url?.canonicalUrl())
    } else {
        this
    }

internal fun CaldavCalendar.canonicalizedBackupUrl(account: CaldavAccount?): CaldavCalendar =
    if (account != null &&
        (account.accountType == CaldavAccount.TYPE_CALDAV ||
            account.accountType == CaldavAccount.TYPE_TASKS)
    ) {
        copy(url = url?.canonicalUrl())
    } else {
        this
    }

private val LEGACY_PICTURE_JSON = Json { isLenient = true }

/**
 * Port of the Android `convertPictureUri` (pre-6.4 comment pictures):
 * picture values serialized as `{"uri": ...}` or `{"path": ...}` JSON
 * become plain URIs.
 */
fun convertLegacyPictureUri(value: String?): String? {
    if (value.isNullOrBlank()) return null
    if (!value.contains("uri") && !value.contains("path")) return null
    return try {
        val obj = LEGACY_PICTURE_JSON.parseToJsonElement(value) as? JsonObject ?: return null
        obj["uri"]?.jsonPrimitive?.content
            ?: obj["path"]?.jsonPrimitive?.content?.let { path ->
                // Mirrors Uri.fromFile(File(path)): absolute paths become
                // file://<path> (three slashes), relative ones file:///<path>.
                if (path.startsWith("/")) "file://$path" else "file:///$path"
            }
    } catch (e: Exception) {
        null
    }
}
