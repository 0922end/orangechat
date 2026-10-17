# Elian Development Skill

## Project Overview
This is Elian (forked from orangechat/rikkahub), an Android app built with Kotlin + Jetpack Compose.
Package: com.elian.app.debug
Build: GitHub Actions (release.yml, workflow_dispatch)
Repo: 0922end/orangechat (master branch)

## Key Directories
- app/src/main/java/me/rerere/rikkahub/ - Main source code
- app/src/main/java/me/rerere/rikkahub/ui/ - UI components (Compose)
- app/src/main/java/me/rerere/rikkahub/data/ - Data layer
- app/src/main/java/me/rerere/rikkahub/xinchao/ - Xinchao (心潮) integration
- signing/debug.keystore - Fixed signing key (DO NOT regenerate)

## Build & Push
1. Make changes to source files
2. git add + commit + push to master
3. GitHub Actions builds APK automatically on workflow_dispatch
4. GitHub token: use $GITHUB_TOKEN env var

## Rules
- Keep Windows line endings (\r\n) as existing files use them
- Do not modify signing/debug.keystore
- Do not modify .github/workflows/ (token lacks workflow scope)
- Kotlin + Compose, minimum SDK 26, target SDK 35
- Test compilation locally if possible before pushing
