# Shopee Number Finder — Project State

## Goal
Android helper app for a 5x5 number game. Final behavior: detect positions of 1-25 once, then guide the user through 1-50 using an on-screen overlay. Numbers 26-50 reuse positions of 1-25. No auto-clicking.

## Phase 1
Status: prepared for GitHub Actions cloud build.

Implemented:
- Start/Stop UI
- Display-over-other-apps permission flow
- MediaProjection screen-capture permission flow
- Foreground capture service
- Test overlay service
- GitHub Actions workflow using JDK 17 + Gradle 8.9
- Debug APK artifact upload

## Next validation
Build in GitHub Actions and test on the Android phone that the overlay appears over Shopee and screen-capture permission succeeds.

## Phase 2
After Phase 1 validation: recognize/map 1-25 once and implement sequential highlight logic for 1-50.
