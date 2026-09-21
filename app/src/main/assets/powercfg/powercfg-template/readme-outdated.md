Here you can configure two differently styled mode files for your device. When running from the packaged APK, you can switch mode files in the performance profile screen.

# Conservative mode files
- conservative-base.sh
- conservative.sh

# Aggressive scheduling mode files
- active-base.sh
- active.sh

# Details
- active-base.sh and conservative-base.sh are rarely used. They run only once at the very beginning to revert some user modifications, and can be left empty if not needed.
- active.sh and conservative.sh are the main profile scripts. Define the script code for the 4 modes inside them.

- If you do not want to create a profile by modifying the APK, you can copy your finished powercfg.sh to the data directory [the final profile script path is /data/powercfg.sh] and set its permissions to 0644.
- Note the encoding of the profile script: it must use Unix line endings, otherwise the shell cannot parse it and mode switching will fail.

# Per-app adaptation
- In addition to the four basic performance modes, Scene 4.3 adds Strict Mode (which must be enabled manually in the performance profile screen).
- With Strict Mode enabled, Scene triggers a scheduling switch whenever the foreground app changes.
- * Without Strict Mode, or on versions before Scene 4.3, scheduling switches are triggered only when the mode needs to change.
- How do you know which app was just opened? In the script you can read the `top_app` variable saved by Scene, for example:

```sh
if [[ "$top_app" != "" ]]; then
  echo "App switched to foreground [$top_app]"
fi
```

## Special cases
- Even with Strict Mode enabled, you may still get an empty `$top_app`
- This is because the scheduling switch was not triggered by the dynamic response feature
- For example, switches triggered by manual taps, scheduled tasks, screen off/on, or running init will all produce an empty `$top_app`
