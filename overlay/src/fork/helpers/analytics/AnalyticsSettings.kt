package desu.inugram.helpers.analytics

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.MainTabsMenuConfig
import desu.inugram.helpers.pillstack.PillStackLayout
import desu.inugram.helpers.pillstack.PillType
import org.json.JSONObject

object AnalyticsSettings {
    const val VERSION = 1

    private val booleanKeys = """
        hide_stories disable_overscroll_story show_seconds
        hide_reply_background disable_reply_custom_colors hide_reply_background_emoji
        disable_rounding material3_switches material3_sliders
        material3_fabs m3_sections_style hide_dividers
        m3_headers_in_card m3_accent_icons forum_m3_sections
        dialogs_m3_sections dialogs_m3_separate_pinned material3_avatars
        unified_avatar_radius material_profile_actions m3_navigation_animation
        md3_player md3_player_online_lyrics m3_bottom_tabs
        plugins_enabled plugins_compact_list plugins_dev_mode
        show_spoilers_directly hdr_images spoiler_extend_to_line_end
        link_preview_spoiler disable_link_previews blocked_messages_hide_replies
        gif_seekbar alternative_gif_behavior play_original_video
        play_original_video_inline sleep_timer_end_of_track shorter_swipe_gestures
        disable_video_message_autoplay send_mp4_document_as_video bypass_gif_restrictions
        keep_chats_in_stack sort_albums_by_size attach_camera_square
        attach_camera_mirror_front save_filename_date auto_disable_proxy_on_vpn
        show_all_recent_stickers unlimited_favorite_stickers unlimited_pinned_chats
        unlimited_folder_chats local_folders hide_trending_stickers
        navigation_drawer drawer_back_gesture drawer_keep_options_menu
        drawer_m3_sections show_drawer_accounts drawer_show_badge
        bottom_tabs_hide bottom_tabs_hide_compact_mode bottom_tabs_show_titles
        bottom_tabs_hide_top_search bottom_tabs_search_separate bottom_tabs_hide_on_scroll
        bottom_tabs_disable_profile_swipe dialogs_fab_hide dialogs_fab_hide_on_scroll
        dialogs_fab_offset_for_bottom_bar dialogs_fab_raise hide_dialog_preview
        dialogs_fab_left_side hide_keyboard_on_scroll keep_keyboard_while_typing
        disable_pull_to_next disable_sensitive disable_chat_backgrounds
        disable_chat_themes disable_bg_parallax disable_swipe_to_unarchive
        disable_swipe_to_hide_general_topic disable_contacts_permission_nag disable_lockscreen_permission_nag
        chat_always_show_down chat_two_finger_select chat_remember_all_replies
        instant_mark_reactions_read disable_bot_draft_top mention_separator_for_bots
        hide_bottom_bar_joined hide_bottom_bar_non_joined hide_bottom_bar_non_joined_groups
        hide_bottom_bar_replies hide_bottom_bar_pinned hide_bot_slash_groups
        hide_bot_slash_bots hide_bot_webview_input hide_send_as_picker
        send_to_discuss_without_join chat_snowflakes action_bar_snowflakes
        logs_redact confirm_send_voice confirm_send_sticker
        confirm_send_gif hide_bot_webview_dialogs hide_ai_editor
        ai_summary_enabled ai_reasoning_enabled ai_stream_enabled
        ai_only_answer ai_insert_quote ai_transcribe_enabled
        hide_rich_editor_button hide_message_summary hide_iv_summary
        hide_repost_to_story hide_paid_reaction_upsell hide_caption_limit_upsell
        hide_attach_premium_badges hide_emoji_premium_upsell hide_hashtag_suggestions
        hide_gift_button_input hide_gift_cards_in_chat hide_giveaways
        hide_channel_recommendations disable_call_rating hide_group_sticker_pack
        disable_profile_scroll_snap reduce_profile_motion profile_prefer_media_tab
        show_profile_gift_button disable_motion_photos disable_volume_play_video
        disable_quick_share memory_trim optimized_attachment_menu
        hide_channel_share_button hide_profile_story_button hide_profile_gift_button
        hide_profile_live_actions_button hide_premium_badge hide_collectible_status
        hide_stars_rating hide_verification_badge hide_profile_colorful_background
        hide_gifts_around_avatar hide_profile_gifts_tab hide_similar_channels_tab
        hide_profile_icons disable_profile_music_autoplay disable_next_audio_preload
        disable_next_music_autoplay disable_next_voice_autoplay disable_animated_avatars
        hide_reactions_entry hide_suggestion_birthday_setup hide_suggestion_birthday_contacts
        hide_suggestion_password hide_suggestion_phone hide_suggestion_premium
        hide_suggestion_custom hide_proxy_sponsor_chat hide_business_bot_banner
        hide_psa_promo_chat hide_gift_auctions_hint hide_cache_hint
        delete_for_both_messages delete_for_both_dms delete_for_both_groups
        small_gifs hide_all_chats_tab remember_selected_folder
        folders_infinite_swipe tab_indicator_stroke folders_at_bottom
        hide_archive_from_chat_list unread_chats_first unread_chats_first_pinned
        show_online_indicator presence_color_dots chat_presence_dots
        dialogs_title_text_override_archive call_confirmation hd_bluetooth_call_audio
        call_rotation disable_call_proximity force_relay_calls
        presence_logger_notify confirm_internal_links skip_link_confirm
        disable_browser_swipe_collapse confirm_reaction_non_member hide_reactions_display
        hide_reactions_set hide_reactions_badges hide_call_action_button
        show_profile_reg_date settings_show_accounts disable_chat_bubbles
        web_preview_replacements_enabled web_preview_replace_on_paste strip_tracking_params
        strip_tracking_params_on_paste resolve_trackable_redirects disable_intro_sticker
        disable_draft_upload round_recorder_keep_zoom round_recorder_mirror_front
        round_recorder_zoom_slider round_recorder_zoom_buttons round_recorder_exponential_zoom
        round_recorder_dual_camera round_recorder_60fps round_recorder_lock_exposure
        round_recorder_exposure_button round_recorder_exposure_levels non_island_folders_bar
        non_island_shared_media_tabs non_island_global_search non_island_chat_elements
        hide_fade_view disable_scrim_blur disable_glass_glare
        hide_donate_card disable_profile_avatar_blur wide_channel_posts
        reduce_menu_motion profile_photo_gradient_fade simple_attach_popup_animation
        keep_screen_on_recording chat_voice_in_attach chat_views_bottom
        disable_chat_title_phone search_from_global hide_my_phone_number
        ios_button_placement ios_input_appearance compact_input_size
        ios_input_commands_near_stickers reaction_bar_below pinned_reactions_enabled
        old_mention_indicator show_forward_time compact_edited
        show_forwards_count show_media_size forward_pro
        forward_pro_replace forward_pro_silent forward_pro_hide_caption
        forward_pro_show_sender forward_pro_comment_after bubble_tails
        disable_chat_preview_expand formatting_popup suggest_custom_emoji_after
        selection_process_text message_menu_bottom_row message_menu_quick_actions_top
        group_entiny_menu group_chat_archives hide_profile_menu_send_gift
        hide_profile_menu_archived_stories center_title_main center_title_chats
        center_title_right_avatar ios_bottom_navigation_bar ios_chats_tab_returns_to_first_folder
        dialog_avatar_opens_profile always_show_downloads hide_downloads_button
        ios_chat_header ios_chat_list ios_message_menu
        hide_chat_avatar ios_chat_header_avatar_slot ios_chat_header_avatar_swap
        ios_chat_header_avatar_static chat_header_no_pill clown_avatar_blocked
        clown_avatar_blocked_me chat_title_marquee unread_badge_on_back_button
        unread_badge_on_back_button_ios save_self_destruct_media save_self_destruct_text
        save_view_once_media view_once_show_normal one_time_peek
        auto_save_one_time save_timed_messages save_any_story
        save_selected_files local_names local_message_edit
        auto_save_stories save_deleted_messages save_deleted_own
        archive_media_public allow_forward_restricted allow_screenshots
        suppress_screenshot_notification hide_sponsored_messages save_edited_messages
        archive_media_enabled show_edit_history_diff save_user_info
        save_read_time save_last_seen show_mutual_contact_icon
        show_mutual_contact_in_chats mutual_contact_icon_left mask_server_app_name
        regex_filter_enabled regex_filter_hide_replies notif_channel_sync
        per_chat_notif_channels updates_enabled updates_include_beta
        unified_push foreground_push_service device_spoof
        device_spoof_hide_tz device_spoof_hide_installer minimize_stickers_creator
        cloud_sync_auto event_log_char_diff in_place_translation
        translate_web_previews keep_original_after_translation translate_auto_detect_lang
        force_translate auto_translate_all translate_outgoing
        input_translate translate_on_send translate_in_send_preview
        translate_auto_fallback translate_new_google instant_translate_banner
        ignore_translations_disabled account_switch_shortcut recent_chats_enabled
        hide_recent_chats_shortcuts chat_export notify_bell_in_menu
        chat_export_html faster_downloads faster_downloads_ultra
        always_send_videos_hd faster_uploads keep_downloads_in_background
        block_sleep_while_downloading biometric_confirm_delete_chat biometric_confirm_logout
        biometric_require_confirmation_passcode biometric_require_confirmation_bots biometric_require_confirmation_actions
        biometric_allow_device_credential biometric_lock_archive biometric_lock_archive_every_time
        ghost_mode_enabled ghost_hide_read ghost_read_on_send
        ghost_mark_read_locally ghost_hide_seen_mentions ghost_hide_voice_read
        ghost_hide_story_read ghost_hide_typing ghost_hide_app_bar_icon
        ghost_mode_launcher_shortcut ghost_scope_users ghost_scope_groups
        ghost_scope_channels local_premium local_custom_emoji
        hide_dev_badges deleted_messages_transparent open_by_user_id
        selection_bottom_no_quote feed_enabled feed_include_archived
        feed_newest_on_top feed_mark_read_on_scroll pill_stack_enabled
        pill_stack_infinite_scroll pill_stack_in_header pill_stack_proxy_country
        weather_use_current_location weather_fahrenheit font_include_system
        font_synthetic_bold
    """.trimIndent().split(Regex("\\s+")).toSet()

    private val modeValues = mapOf(
        "predictive_back_mode" to intArrayOf(0, 1, 2),
        "clock_format" to intArrayOf(0, 1, 2),
        "text_spoiler_mode" to intArrayOf(0, 1, 2),
        "media_spoiler_mode" to intArrayOf(0, 1, 2),
        "blocked_messages_mode" to intArrayOf(0, 1, 2),
        "attach_camera_mode" to intArrayOf(0, 1, 2, 3),
        "pull_down_action" to intArrayOf(0, 1, 2, 3, 4),
        "folders_display_mode" to intArrayOf(1, 2, 3),
        "folders_unread_counter_mode" to intArrayOf(0, 1, 2, 3),
        "community_display_mode" to intArrayOf(1, 2, 3),
        "dialogs_title_text" to intArrayOf(1, 2, 3, 4, 5, 6),
        "sticker_time_mode" to intArrayOf(1, 2, 3, 4),
        "presence_logs_ttl" to intArrayOf(0, 1, 7, 30),
        "profile_id_mode" to intArrayOf(0, 1, 2),
        "forward_header_mode" to intArrayOf(0, 1, 2),
        "edited_message_date_mode" to intArrayOf(0, 1, 2),
        "text_classifier_mode" to intArrayOf(1, 2, 3),
        "forward_long_tap_action" to intArrayOf(0, 1, 2, 3),
        "reply_long_tap_action" to intArrayOf(0, 1, 2, 3),
        "repeat_mode" to intArrayOf(0, 1, 2),
        "deleted_messages_ttl" to intArrayOf(0, 1, 7, 30),
        "regex_filter_mode" to intArrayOf(0, 1),
        "notification_icon" to intArrayOf(0, 1, 2),
        "map_provider" to intArrayOf(0, 2),
        "map_preview_provider" to intArrayOf(0, 1, 2, 4),
        "recent_chats_style" to intArrayOf(0, 1, 2, 3),
        "ghost_presence_mode" to intArrayOf(0, 1, 2),
        "deleted_mark_style" to intArrayOf(0, 1, 2, 3, 4, 5),
    )

    fun snapshot(): JSONObject {
        val items = InuConfig.items.associateBy { it.key }
        fun enabled(key: String) = (items[key] as? InuConfig.BoolItem)?.value == true
        fun mode(key: String) = (items[key] as? InuConfig.IntItem)?.value
        val archiveEnabled = enabled("save_deleted_messages") || enabled("save_edited_messages")
        val preserveMedia = enabled("save_self_destruct_media") || enabled("save_view_once_media")
        val compactHeader = enabled("center_title_chats") && enabled("ios_chat_header")
        val avatarVisible = !enabled("hide_chat_avatar")
        val avatarSlot = compactHeader && avatarVisible && enabled("ios_chat_header_avatar_slot")
        val avatarStatic = compactHeader && avatarVisible && !avatarSlot && enabled("ios_chat_header_avatar_static")
        val activePills = if (enabled("pill_stack_enabled")) PillStackLayout.getActivePills() else emptyList()
        fun active(key: String): Boolean = when (key) {
            "m3_headers_in_card" -> enabled("m3_sections_style")
            "dialogs_m3_separate_pinned" -> enabled("dialogs_m3_sections")
            "md3_player_online_lyrics" -> enabled("md3_player")
            "plugins_compact_list", "plugins_dev_mode" -> enabled("plugins_enabled")
            "text_spoiler_mode", "media_spoiler_mode", "spoiler_extend_to_line_end" -> !enabled("show_spoilers_directly")
            "link_preview_spoiler" -> !enabled("show_spoilers_directly") && !enabled("disable_link_previews")
            "blocked_messages_hide_replies" -> mode("blocked_messages_mode")?.let { it in 1..2 } == true
            "play_original_video_inline" -> enabled("play_original_video")
            "drawer_back_gesture", "drawer_keep_options_menu", "drawer_m3_sections",
            "show_drawer_accounts", "drawer_show_badge" -> enabled("navigation_drawer")
            "bottom_tabs_hide_compact_mode", "bottom_tabs_hide_on_scroll",
            "bottom_tabs_disable_profile_swipe", "m3_bottom_tabs" -> !enabled("bottom_tabs_hide")
            "bottom_tabs_show_titles" -> !enabled("bottom_tabs_hide") && !enabled("bottom_tabs_hide_compact_mode")
            "bottom_tabs_search_separate" -> !enabled("bottom_tabs_hide") && !enabled("m3_bottom_tabs") &&
                InuConfig.BOTTOM_TABS_ORDER.value.any { it.item == MainTabsMenuConfig.Item.SEARCH && it.enabled }
            "ios_bottom_navigation_bar" -> !enabled("bottom_tabs_hide") && !enabled("m3_bottom_tabs")
            "ios_chats_tab_returns_to_first_folder" -> !enabled("bottom_tabs_hide") &&
                !enabled("m3_bottom_tabs") && enabled("ios_bottom_navigation_bar")
            "dialogs_fab_hide_on_scroll", "dialogs_fab_offset_for_bottom_bar",
            "dialogs_fab_raise", "dialogs_fab_left_side" -> !enabled("dialogs_fab_hide")
            "disable_bg_parallax" -> !enabled("disable_chat_backgrounds")
            "mention_separator_for_bots" -> (items["mention_separator"]?.value as? String)?.isNotEmpty() == true
            "unread_chats_first_pinned" -> enabled("unread_chats_first")
            "presence_color_dots" -> enabled("show_online_indicator")
            "chat_presence_dots" -> enabled("show_online_indicator") && enabled("presence_color_dots")
            "show_mutual_contact_in_chats", "mutual_contact_icon_left" -> enabled("show_mutual_contact_icon")
            "web_preview_replace_on_paste" -> enabled("web_preview_replacements_enabled")
            "resolve_trackable_redirects" -> enabled("strip_tracking_params") || enabled("strip_tracking_params_on_paste")
            "round_recorder_exposure_levels" -> enabled("round_recorder_exposure_button")
            "ios_input_commands_near_stickers" -> enabled("ios_button_placement") && enabled("ios_input_appearance")
            "compact_input_size" -> enabled("ios_input_appearance")
            "forward_pro_replace", "forward_pro_silent", "forward_pro_hide_caption",
            "forward_pro_show_sender", "forward_pro_comment_after" -> enabled("forward_pro")
            "ios_chat_header" -> enabled("center_title_chats")
            "ios_chat_header_avatar_slot" -> compactHeader && avatarVisible
            "ios_chat_header_avatar_swap" -> avatarSlot
            "ios_chat_header_avatar_static" -> compactHeader && avatarVisible && !avatarSlot
            "center_title_right_avatar" -> enabled("center_title_chats") && avatarVisible && !avatarSlot && !avatarStatic
            "unread_badge_on_back_button_ios" -> enabled("unread_badge_on_back_button")
            "view_once_show_normal", "one_time_peek", "auto_save_one_time" -> preserveMedia
            "save_deleted_own", "deleted_messages_transparent", "deleted_mark_style" -> enabled("save_deleted_messages")
            "show_edit_history_diff" -> enabled("save_edited_messages")
            "archive_media_enabled", "deleted_messages_ttl" -> archiveEnabled
            "archive_media_public" -> archiveEnabled && enabled("archive_media_enabled")
            "regex_filter_hide_replies", "regex_filter_mode" -> enabled("regex_filter_enabled")
            "updates_include_beta" -> enabled("updates_enabled")
            "device_spoof_hide_tz", "device_spoof_hide_installer" -> enabled("device_spoof")
            "translate_web_previews", "keep_original_after_translation" -> enabled("in_place_translation")
            "recent_chats_style", "hide_recent_chats_shortcuts" -> enabled("recent_chats_enabled")
            "chat_export_html" -> enabled("chat_export")
            "faster_downloads_ultra" -> enabled("faster_downloads")
            "block_sleep_while_downloading" -> enabled("keep_downloads_in_background")
            "biometric_lock_archive_every_time" -> enabled("biometric_lock_archive")
            "ghost_hide_read", "ghost_hide_voice_read", "ghost_hide_story_read", "ghost_hide_typing",
            "ghost_presence_mode", "ghost_hide_seen_mentions", "ghost_hide_app_bar_icon",
            "ghost_scope_users", "ghost_scope_groups", "ghost_scope_channels" -> enabled("ghost_mode_enabled")
            "ghost_read_on_send", "ghost_mark_read_locally" -> enabled("ghost_mode_enabled") && enabled("ghost_hide_read")
            "feed_include_archived", "feed_newest_on_top", "feed_mark_read_on_scroll" -> enabled("feed_enabled")
            "pill_stack_infinite_scroll", "pill_stack_in_header" -> enabled("pill_stack_enabled")
            "pill_stack_proxy_country" -> PillType.PROXY.id in activePills
            "weather_use_current_location", "weather_fahrenheit" -> PillType.WEATHER.id in activePills
            else -> true
        }

        return JSONObject().apply {
            for (key in booleanKeys) {
                if (enabled(key) && active(key)) put(key, true)
            }
            for ((key, allowed) in modeValues) {
                val value = mode(key)
                if (value != null && value in allowed && active(key)) put(key, value)
            }
        }
    }
}

