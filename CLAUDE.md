# Code Style
When working with this codebase, prioritize readability over cleverness. Ask clarifying questions before making architectural changes. 
Put comments only when absolutely necessary or documenting. It only has a single module :app

# About this project
Curbox is an advanced screentime management tool for android that utilizes accessibility services to work. 

# Architecture
The app follows a compartmentalization style. There are only two accessibility services:
1) Usage Tracking : Features that track usage
2) App Blocker : Features that perform actions like home press, back press etc (declared as a separate android process)

Each feature (like app blocking, app usage stats, keyword blocking) is a compartmental class with a service object. 
This class obj is created in accessibility services, and the service instance is passed to the feature in the onServiceConnected() method.
All function responsible for setting up the feature(like loading configs), broadcast receiver are aswell declared in this method itself.

The App blocker service runs low memory features (like app blocking) in the onAccessibilityEvent itself while high memory consuming tasks (reelblocking, Ui hider) 
that traverse the entire ui node, run in a background worker that is fed with event updates continuously.
Hardcoded viewids for blocking are always stored separately in hardcoded folder.

All the features that run in AppBlockerService are declared in the blockers folder and for UsageTrackingService, its trackers folder

The app should ensure no matter what happens the AppBlockingService, Usage tracking service never crash. 

# Features
## App blocker Service:
Folder /Users/adityagupta/Documents/projects/curbox/app/src/main/java/neth/iecal/curbox/blockers
├── AppBlocker.kt
├── BaseBlocker.kt
├── BrowserBlocker.kt
├── FocusModeBlocker.kt
├── KeywordBlocker.kt
├── ReelBlocker.kt
└── Ui hider (is a folder)

- AppBlocking
- Reel blocking(AppBlocker.kt) :block instagram reels, youtube shorts, facebook reels)
- Keyword Blocking: block websites, regex, keyword. This service is not responsible for searching keywords on screen, but only analyze the live updates fed by 
WebsiteUsageTracker and decide whether a block is needed or not
- Focus Mode Blocker: Temporarily blocks apps and keywords for a set duration(like 5 mins) so user can focus on a task like studying.
- View Blocker: Uses an overlay to hide specific ui elements or areas on screen when they're opened.

## Usage Tracking Service:
Folder: /Users/adityagupta/Documents/projects/curbox/app/src/main/java/neth/iecal/curbox/trackers
├── AppUsageTracker.kt
├── ReelsCountTracker.kt
└── WebsiteUsageTracker.kt

- AppUsageTracker: Tracks and stores app usage analytics in a room db
- ReelsCountTracker: Counts the number of short-form video content you scroll.
- WebsiteUsageTracker: Tracks and stores website analytics in a room db

# Storing data and models
The /Users/adityagupta/Documents/projects/curbox/app/src/main/java/neth/iecal/curbox/data/models 
folder stores raw data classes and models

The /Users/adityagupta/Documents/projects/curbox/app/src/main/java/neth/iecal/curbox/data/db
folder pools together all db releated objects including their data class

The project uses room database to store large info
while datastore for stuff like configuration.

# UI
- Android dynamic colors
- Uses a combination of ascii art, typography, minimalist and material ui.
- Use default values mostly for text colors, button colors etc

## Fonts
- Coolvetica: Used for extreme typographical and hooky screens like onboarding
- Inter : Used for most of the app


# UX
- Doesn't overwhelm users
- calming and peaceful
- smooth animations between views resizing, disappearing, appearing etc

## Writing user displayed text
- Never use dashes(-) anywhere
- Keep simple language at a 6th grader level
- Speak in first person
- Be crisp and concise
- Explain with real world examples if too complex
- The reader is not tech savy

# Overall Java Code Structure
Main working directory: /Users/adityagupta/Documents/projects/curbox/app/src
└── main
├── java
│   └── neth
│       └── iecal
│           └── curbox
│               ├── anti_stimulants
│               ├── blockers
│               │   └── Ui hider
│               ├── data
│               │   ├── db
│               │   └── models
│               ├── hardcoded
│               ├── receivers
│               ├── services
│               ├── trackers
│               ├── ui
│               │   ├── activity
│               │   ├── fragments
│               │   │   ├── installation
│               │   │   │   └── onboarding
│               │   │   │       └── screens
│               │   │   └── main
│               │   │       ├── focus
│               │   │       ├── reducers
│               │   │       │   ├── analytics
│               │   │       │   ├── anti_stimulants
│               │   │       │   │   ├── grayscale
│               │   │       │   │   ├── mindful_messages
│               │   │       │   │   └── reel_counter
│               │   │       │   └── blockertools
│               │   │       │       ├── appBlocker
│               │   │       │       ├── autodnd
│               │   │       │       ├── keywordBlocker
│               │   │       │       ├── reelBlocker
│               │   │       │       ├── shared
│               │   │       │       └── Ui hider
│               │   │       └── usage
│               │   ├── overlay
│               │   ├── views
│               │   └── widgets
│               ├── utils
│               └── views
└── res
├── anim
├── drawable
├── font
├── layout
├── menu
├── mipmap-anydpi-v26
├── values
├── values-night
└── xml
