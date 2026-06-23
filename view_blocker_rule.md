# Curbox ViewBlocker Rule Syntax

The ViewBlocker uses a flexible, search-query-like syntax for powerful and concise element blocking on screen.

## Core Syntax Format
Rules are defined as space-separated `key:value` pairs. 
- Keys are **case-insensitive** (e.g. `pkg:` is identical to `PKG:`).
- Values containing spaces must be encapsulated in double-quotes, like `text:"Block This Ad"`.
- If a value does not contain spaces, quotes are optional.
- Unrecognized keys are gracefully ignored.

## Base Properties
Here are the supported target fields mapping to elements on the screen:

- `pkg:` - **(Required)** Target application package (e.g., `pkg:com.android.settings`)
- `id:` - Exact ViewID of the target element. 
- `class:` - Exact class name of the target element (e.g. `class:android.widget.TextView`)
- `text:` - Target element's exact text.
- `desc:` - Content Description of the target element. You can provide this multiple times by adding commas (e.g. `desc:Refresh,Reload`). 
- `path:` - Hierarchical element path (e.g., `path:FrameLayout[0]>TextView[1]`)

### Example:
`pkg:com.youtube.app id:ad_container_view`

---

## Action & Toggles
Define what action occurs once a blocked view is successfully matched:

- `action:` - `overlay` (default) places a colored block over it; `back` instantly forces a system back-press event instead of rendering over it. 
- `color:` - Hexadecimal overlay color (e.g., `color:#000000`).
- `blocktouches:` - `true` (default) intercepts touches preventing clicks beneath the layout. Set to `false` or `0` for an unclickable informational overlay. 
- `max:` - Limit on matches per screen. (e.g., `max:1`).

### Example:
`pkg:com.google.android.youtube action:back max:1`

---

## Advanced Matching
For fuzzy matching, standard wildcards can be integrated:
- `textcontains:` - Text must be part of the element's actual text. 
- `desccontains:` - Substring required in the content description.
- `text~:` - Regular expression match against the node's text.
- `desc~:` - Regular expression match against the description.

---

## Modifiers (Conditions)
To block elements *conditionally*, you can require the presence/absence of other screen nodes using prefixes:

- **Require Present (`+`)**: Matcher executes only if the prefixed node exists on the screen.
- **Require Absent (`-`)**: Matcher executes only if the prefixed node is missing!
- **Match Children (`~`)**: Used against child layouts traversing nested tree matches.

Modifiers are parsed into `NodeMatcher` rules, accepting matching subtypes:
`id`, `desc`, `text`, `class`, `path`, `textcontains`, `isselected`, `ischecked`, `isfocused`, `isenabled`, `isclickable`.

### Modifier Examples:
- Wait for loading to finish: `pkg:com.app.xyz class:Button -id:loading_spinner`
- Ensure confirmation exists: `pkg:com.app.xyz action:back +text:"Retry Payment" `

---

## Example Complex Rule:
```
pkg:com.android.settings id:action_bar action:back +text:"Force Stop" -id:loading_spinner
```
This clicks back (`action:back`) out of `com.android.settings` at `action_bar`, but **only if** the `Force Stop` text label is on the identical screen AND the `loading_spinner` exists no longer.
