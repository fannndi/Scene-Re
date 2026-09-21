# Scene

> Before using this tool, please make sure you have ROOT phone and busybox installed. You'd better be a gaming expert, at least you should also be able to refresh and repair the system. Because some advanced functions in the application may affect the normal startup of the system! ! !
> It has the best experience on devices using [Snapdragon 845/835/821/820] [Exynos8890] processor, and other device functions will be limited.

---

## Security / compatibility notes

Recent security hardening changed a few externally visible behaviors. If you drive Scene from automation apps (Tasker, MacroDroid, etc.), read this:

- `SceneTaskIntentService` is no longer exported. External apps can no longer start timing tasks by sending the `com.omarea.scene_mode.TimingTaskReceiver` action. Timing tasks still run normally from alarms and from inside the app.
- `ActionPageOnline` is no longer exported. External apps can no longer open arbitrary web pages with the kr-script root bridge attached. Pages opened from Scene itself (including shortcuts and add-ins) are unaffected.
- `ActionPage` still accepts pinned shortcuts (`shortcutId`), but serialized `page` payloads are only accepted from Scene itself. Shortcuts and favorites created by the app continue to work.
- `ReceiverShortcut` is no longer exported. Pinned shortcut callbacks (created by Scene) still work.
- Cross-app unfreeze through `SceneFreezeProvider` now verifies that the `source` package really belongs to the calling UID, and only acts on installed packages.
- Cleartext HTTP is disabled by default; only the official Scene domains are exempted. Update checks now use the GitHub Releases API over HTTPS.

---

No detailed description

---

**Some app screenshot**

<img src="https://user-images.githubusercontent.com/14274061/138872394-c27b859a-4cf0-4abf-ada8-3210de240ae9.jpg" width="320"> <img src="https://user-images.githubusercontent.com/14274061/138872398-b8475111-1005-46ea-a5fa-b9ccff685eb1.jpg" width="320">
<img src="https://user-images.githubusercontent.com/14274061/138872401-0ac5f127-0bd9-4908-8168-f470b6ad0dfb.jpg" width="320"> <img src="https://user-images.githubusercontent.com/14274061/138872402-e4256e0b-9651-4ddd-99eb-11627344147b.jpg" width="320">
<img src="https://user-images.githubusercontent.com/14274061/138872405-094a32fe-6e44-4e27-a8b4-4fd6c540898c.jpg" width="320"> <img src="https://user-images.githubusercontent.com/14274061/138872384-b98fe241-fbbf-4114-ab4f-df7832b501ce.jpg" width="320">
