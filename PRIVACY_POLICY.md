# Privacy Policy for Curbox

**Last updated: June 18, 2026**

Thanks for using Curbox. This policy explains, in plain words, what the app does with your information. The short version is easy to remember: Curbox keeps everything on your phone. We never see your data, and neither does anyone else.

Curbox is a screen time and digital wellbeing app. It helps you block apps and websites, focus, and understand your own habits. To do that, the app needs to look at what is happening on your screen. We know that sounds sensitive, so we built Curbox so that none of it ever leaves your device.

## The most important thing to know

Curbox does not have permission to use the internet.

We did not add the internet permission to the app. You can check this yourself in the app's permission list or in the source code. Because the app cannot reach the internet, it is technically impossible for it to send your information to us, to a server, or to any other company. There are no accounts to create, nothing to log in to, and no cloud backup that we control.

Your data lives on your phone and stays there.

## What information Curbox uses

Curbox works with a few kinds of information, and all of it is stored only on your device in a local database and local settings files.

**App usage.** The app records which apps you open, how long you use them, how many times you open them, and when you last used them. This is what powers your usage charts and screen time stats.

**Website usage.** When you browse in a supported browser, Curbox notes the website's domain and the section you are on (for example, "youtube.com/shorts") along with how long you spend there. It does not save the full web address. Things like search terms, query parameters, and the rest of the link are dropped. We keep just enough to group your time by site.

**Short video counts.** If you turn on the reel counter, the app counts how many short videos (like Reels or Shorts) you scroll through, and stores a daily total.

**Focus sessions.** When you run a focus session, the app saves when it started, how long it was meant to last, when it actually ended, and whether you finished it.

**Blocked attempts.** When a block stops you from opening something, the app may log that moment so you can review it later in the analytics screen. This can include the name of the app you tried to open and how long it was unblocked for.

**Your settings.** This includes your block lists, focus groups, keyword lists, warning messages, mindful messages, and the apps you have chosen to manage.

**Screen content, while the app is running.** Curbox uses Android's Accessibility service. This lets the app read what is on your screen, such as the name of the app in front of you or the address in your browser bar. The app reads this in the moment to decide whether to block something or count it. It is processed on your device as it happens and is not collected into a profile or sent anywhere.

## What Curbox does not do

* It does not connect to the internet.
* It does not send your data to us or to anyone else.
* It does not sell or share your data. There is no one to share it with.
* It does not contain ads.
* It does not contain analytics, tracking, or crash reporting tools that phone home.
* It does not read or store your messages, photos, contacts, passwords, or the content you type, beyond what is needed in the moment to block or count something.

## Permissions and why they are needed

Curbox asks for a number of permissions so it can do its job. None of them are used to gather data about you for anyone else. Below is each permission, what it actually does inside the app, and why the app would not work properly without it. You stay in control and can turn most of these off in your phone's settings, though the related feature will stop working when you do.

### The two Accessibility services

Curbox runs two separate Accessibility services. Splitting them keeps the app stable and lets you understand exactly what each one is for. Both read screen information only while they are running, only to do the job described, and only on your device. Nothing they see is stored as a profile or sent anywhere.

**App Blocker service.** This service watches which app or website is in front of you so it can act on it the moment it appears. It is what powers app blocking, website and keyword blocking, short video (reel) blocking, focus mode, and the warning and mindful message screens. When it sees something on your block list, it can press back or home for you, or show a block screen on top, so you are gently pulled away from it. To stay quick and reliable, it reads the current screen as events happen and decides in that instant whether a block is needed.

**Usage Tracking service.** This service measures how you actually spend your time so the app can show you honest stats. It records how long you stay in each app, how many times you open it, and when. For supported browsers it notes the website domain and section you are on (not the full link) and how long you spend there. It also counts short videos for the reel counter if you turn that on. All of this is written to a database on your phone and used only to draw your charts and history.

Both services are core functionality, not optional extras. Without Accessibility access, Curbox cannot block anything or measure anything, and the app cannot do its job.

### Foreground service

**Run as a foreground service (special use).** Curbox runs its blocking and tracking work as a foreground service. On Android, a foreground service is one that stays active in the background with a visible notification, instead of being quietly killed by the system. Curbox needs this because blocking and tracking have to keep working the whole time you use your phone, not just while the app is open on screen. The "special use" type tells Android, and you, that the service exists for digital wellbeing: app blocking and usage monitoring. This permission is about staying alive to do its job. It does not collect any extra information.

### Other permissions

**See other apps installed (query all packages).** Curbox shows you a list of the apps on your phone so you can choose which ones to block, track, or include in a focus session. To build that list and to match the app in front of you against your block list, it needs to know which apps are installed. The list of your apps stays on your device.

**Display over other apps (system alert window).** This lets Curbox draw on top of whatever you are using. It is how block screens, warnings, mindful messages, and the reel counter overlay appear over the app you are trying to open. Without it, Curbox could decide to block something but would have no way to show you the screen that stops you.

**Notifications.** Android requires a foreground service to show a notification, and the app uses notifications to tell you what it is doing, such as that a focus session is running. This keeps the background work honest and visible to you.

**Do Not Disturb access.** This is used by the Auto DND feature so the app can turn Do Not Disturb on and off for you on the schedule you set. It only changes the Do Not Disturb state. It does not read your notifications.

**Vibrate.** Used for small haptic feedback inside the app, such as a light buzz when something is blocked or confirmed.

**Camera.** Used in only one place: if you set up an unlock that asks you to scan a QR code or barcode before a block lifts (for example, scanning a code on a product box in another room to add a moment of friction before unlocking). The camera turns on live only while you are scanning, and turns off right after. No photos are taken, and nothing from the camera is saved or sent anywhere.

**Shizuku (optional).** If you choose to connect Shizuku, Curbox can use it for features like turning your screen grayscale or pausing apps during a focus session. These run as local system commands on your own device. Shizuku is a separate tool with its own setup, and nothing about it sends data over a network.

## Crash logs

If the app ever crashes, it may save a crash log file on your phone to help with fixing bugs. This file stays on your device. We never receive it automatically. If you want to help us fix a problem, you can choose to share a crash log yourself using your phone's normal share menu, and pick where it goes (for example, a chat app or email). That is entirely your decision, and once you send it through another app, that app's own privacy rules apply.

## Children

Curbox is not designed for or directed at children under 13. It is a tool for managing your own screen habits. Because the app does not collect or transmit any personal data, it does not knowingly gather information from children.

## Your control over your data

Because everything is local, you are always in charge:

* You can clear or reset data inside the app.
* You can revoke any permission in your phone's settings.
* You can uninstall Curbox at any time. Uninstalling removes the app's local database and settings from your device. (If you have made your own separate backups through your phone or another tool, those are outside our control.)

## Open source

Curbox is open source. If you ever doubt anything in this policy, you can read the code yourself and confirm how the app handles your information.

## Changes to this policy

If we change how the app works in a way that affects your privacy, we will update this policy and change the date at the top. Since the app has no internet access, the best way to stay current is to check this document in the app's listing or its public repository.

## Contact

If you have questions about this policy or how Curbox handles data, please reach out:

* GitHub: https://github.com/nethical6/curbox
* Discord: https://discord.com/invite/Vs9mwUtuCN
* Telegram: https://t.me/curboxapp
* Email: questphone.app@proton.me

We are happy to help.
