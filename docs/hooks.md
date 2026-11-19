# hooks

реєстр правок host, які робить накладка. генерується, руками не правити:

```powershell
bun extera-plugins/apply.ts --map > extera-plugins/docs/hooks.md
```

регенеруй після кожної зміни `manifest/edits-*.json`. `net lines` рахує різницю рядків `after` мінус `before`.

| area | file | hunks | net lines |
| --- | --- | --- | --- |
| docs | `FEATURES.md` | 1 | +2 |
| fork | `src/fork/InuConfig.kt` | 2 | +3 |
| fork | `src/fork/InuHooks.kt` | 4 | +13 |
| fork | `src/fork/helpers/CrashReporter.kt` | 2 | +6 |
| fork | `src/fork/helpers/chat/ChatActionsHelper.kt` | 2 | +2 |
| fork | `src/fork/helpers/chat/ChatHelper.kt` | 2 | +2 |
| fork | `src/fork/helpers/dialogs/DrawerHelper.kt` | 1 | +1 |
| fork | `src/fork/helpers/plugins/PluginManager.kt` | 4 | +4 |
| fork | `src/fork/helpers/profile/ProfileHelper.kt` | 2 | +2 |
| fork | `src/fork/helpers/update/UpdateHelper.kt` | 6 | +4 |
| fork | `src/fork/ui/settings/PluginInfoActivity.kt` | 2 | +2 |
| fork | `src/fork/ui/settings/PluginInstallSheet.kt` | 11 | +48 |
| fork | `src/fork/ui/settings/PluginsActivity.kt` | 10 | +74 |
| gradle | `worktree/TMessagesProj/build.gradle` | 4 | +6 |
| gradle | `worktree/TMessagesProj_App/build.gradle` | 4 | +25 |
| gradle | `worktree/build.gradle` | 1 | +1 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java` | 4 | +4 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java` | 2 | +42 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java` | 1 | +3 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java` | 1 | +5 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java` | 1 | +14 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/messenger/browser/Browser.java` | 1 | +11 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java` | 2 | +35 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java` | 2 | +6 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/ui/Components/SearchDownloadsContainer.java` | 1 | +3 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/ui/Components/SharedMediaLayout.java` | 1 | +3 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/ui/Components/UItem.java` | 2 | +6 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/ui/FilteredSearchView.java` | 1 | +3 |
| stock | `worktree/TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java` | 5 | +19 |
