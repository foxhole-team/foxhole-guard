# FoxHole Play Review Packet

Checked against current sources: 2026-06-05

This directory contains reviewer-facing drafts for the public `com.foxhole.guard`
release. The files are written to match the current manifest, release gates, and
in-app privacy copy.

## Files

- `privacy-policy.md` - privacy policy draft for the public web page required by
  Google Play.
- `data-safety-draft.md` - Play Console Data safety draft notes.
- `permissions-declarations.md` - permission-by-permission reviewer rationale.
- `vpn-service-declaration.md` - VpnService declaration draft and video notes.
- `fgs-declaration.md` - foreground service `specialUse` declaration draft.
- `query-all-packages-declaration.md` - broad package visibility declaration.
- `reviewer-test-steps.md` - install and review flow steps.
- `review-video-shot-list.md` - shot list for required short review videos.

## Publication Blocker

Replace the privacy contact placeholder in `privacy-policy.md` with the actual
developer/store listing privacy contact before publishing the policy URL or
submitting the Play Console forms. Do not invent this value in code review.

## Official References

- Google Play User Data policy:
  https://support.google.com/googleplay/android-developer/answer/10144311
- Google Play Data safety form:
  https://support.google.com/googleplay/android-developer/answer/10787469
- Google Play VpnService policy:
  https://support.google.com/googleplay/android-developer/answer/12564964
- Google Play QUERY_ALL_PACKAGES policy:
  https://support.google.com/googleplay/android-developer/answer/10158779
- Google Play foreground service declaration help:
  https://support.google.com/googleplay/android-developer/answer/13392821
- Android 14 foreground service type requirements:
  https://developer.android.com/about/versions/14/changes/fgs-types-required
