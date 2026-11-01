# Multi-AI Launcher (Android)

Type a prompt once and send it to several AIs. Each answer appears in its own tab, named after the model it came from.

## How it reaches each AI

| On the toggle bar | How it's reached | What you need |
|---|---|---|
| DeepSeek, Gemini, Claude, ChatGPT, Grok, Venice | The company's API; the answer shows in the app | An API key from that company (Settings, gear icon), then pick a model from the drop-down |
| **Other +** (any installed app, e.g. Copilot, Magica) | Opens that app with your prompt filled in | Nothing; the answer stays in that app |

Android does not let one app type into another app or read its answers, which is why installed apps are opened rather than read. Copilot (consumer app) has no public chat API, and Magica's API is an agent/workflow API rather than a plain chat API, so both are offered through **Other +**; if either is installed, it is added automatically on first launch.

API use is billed by each company on its API account. OpenAI and Anthropic bill API use separately from ChatGPT and Claude app subscriptions.

## Install

Each push builds a signed APK on GitHub (Actions → **Build APK**) and publishes it under **Releases → latest**. Open that page on your phone, download the `.apk`, and allow installs from your browser when Android asks.

## Privacy

API keys are encrypted on the phone with the Android Keystore and are sent only to the company each key belongs to. App backups are turned off so keys are not copied off the phone.

## Build locally (optional)

Open the project in Android Studio and choose **Build > Build APK(s)**, or run `./gradlew assembleRelease`. Unit tests: `./gradlew testDebugUnitTest`.
