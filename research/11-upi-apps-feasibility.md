# UPI and bank apps: can Friday answer "what did I spend last month?"

_Last updated: 2026-10-16 · Trust tags: [primary] read at source · [secondary] search/blog/forum · [measured] run here · [UNCERTAIN] unconfirmed or conflicting_

**Question.** The owner asks "what were my expenses last month". Friday opens the owner's payment app, the owner unlocks it with their own fingerprint, and Friday reads the owner's own transaction history on screen and adds it up. This is read-only: Friday never starts a payment and never touches the UPI PIN.

**Scope: behaviour only.** This note records how the apps behave. It does not research ways around accessibility detection, FLAG_SECURE, root or tamper checks, or any other protection. Where an app blocks Friday, that is a boundary we respect (D-014: "we don't fight it").

**Method.**
- Four parallel web-research passes, plus reads of the AOSP `android-16.0.0_r1` and Flutter `3.35.0` source.
- Every primary source that a conclusion rests on was re-fetched for this note.
- **The phone was not attached, so nothing was run on it.** Every per-app behaviour stays [UNCERTAIN] until it is observed (§8).
- **Search gaps.** The session's shared WebSearch quota (200 calls) ran out partway through. Reddit and Play Store reviews could not be read. So some "no evidence" cells are gaps in the search, not proof of absence.

## TL;DR
1. **No public source says any of the 8 named apps detects an accessibility service as such.** These checks are documented:
   - **Google Pay:** blocks payments and account management while another app draws over the screen. It also blocks its services when it finds "unsafe apps" or a modified OS, citing NPCI's *Mobile Application Security Framework 2025* [primary].
   - **Paytm:** in 2020 it made users uninstall TeamViewer or AnyDesk before it would run [secondary].
   - **ICICI iMobile Pay:** refuses to run while Developer options or USB debugging is on [primary]. **A phone that keeps both on for adb (as our test bench does) can't run iMobile, whatever Friday does.**
   - **Other Indian banks** do block accessibility through security SDKs that run inside the app (known as RASP). HSBC India blocks "accessibility permissions that are incompatible with its safeguards" [primary]. IndusInd showed "untrusted screen reader detected" in May 2025 [secondary].
   - So "banking/UPI apps detect accessibility and refuse to run" (README §4, docs/app.md) is a sound expectation. It is not established for each app.
2. **What the platform does.**
   - Apps that call Play Integrity's app-access-risk check will see Friday as an *unknown* app that can capture or control the screen. Only services that Google Play has reviewed are excluded, so declaring `isAccessibilityTool` would not change that result. D-014 costs nothing here.
   - FLAG_SECURE blanks the pixels but leaves the accessibility tree intact. Paytm uses it on "UPI PIN and confidential transaction information".
   - What hides content from Friday is the data-sensitive mark (`accessibilityDataSensitive`). A view that sets `filterTouchesWhenObscured`, and every view under it, gets the mark automatically [primary, AOSP 16]. If that view is the window root, Friday sees an empty window.
   - No source says whether any of these apps marks amounts this way. Only observation will tell.
3. **Frameworks (Q2).**
   - **Google Pay India is Flutter.** It was rewritten from scratch in 2020 and opened as a beta in India and Singapore [primary]. Whether it is still Flutter in 2026 is [UNCERTAIN].
   - **PhonePe** is mostly native Android, plus its own server-driven UI [primary, 2024].
   - **Paytm** looks native at its core [UNCERTAIN], with Mini Apps that are web pages [primary].
   - **None of the three is React Native at its core.**
   - Flutter builds its accessibility tree (its "semantics tree") whenever *any* accessibility service is on, including Friday's [primary].
4. **Screens that show one monthly figure exist, but each covers only its own app.**
   - **Google Pay:** a figure per month in History. It is **expenses minus money received**, for Google Pay transactions only [primary], so it is not "expenses".
   - **Paytm:** *Spend Summary*, under Balance & History. It covers Paytm UPI payments, by category and by month [primary].
   - **Others:** nothing found for PhonePe or Amazon Pay. HDFC (My Money), ICICI (iFinance) and CRED (CRED Money) have spending views built on the Account Aggregator (AA) framework. BudgetBakers Wallet has reports.
5. **Statement exports exist for every major app.**
   - **Google Pay:** *Get Statement* (PDF) [primary].
   - **Paytm:** UPI statement as PDF since Nov 2024; Excel since Mar 2025 for some UPI handles [secondary].
   - **PhonePe:** statement sent by email [primary].
   - **HDFC:** PDF, Excel, Text, Delimited or MS Money [primary].
   - **ICICI:** e-statements covering 4 years [primary].
   - **SBI:** OnlineSBI, YONO e-statements, or an `ESTMT` SMS that returns a PDF by email [primary].
6. **The Account Aggregator route is closed to a personal app.**
   - Only a regulated entity can receive AA data [primary].
   - The owner's own use inside an AA app is view-only. It covers up to 13 months, the data stays on the device for 7 days, and "auto-fetch and auto-sharing are prohibited" (Sahamati template CT019) [primary]. Don't automate it.
   - India's DPDP data-access right gives only a summary, and only from 13 May 2027 [secondary].
7. **Recommendation, in order.**
   - **First:** the bank statement file, parsed on the phone. It covers every UPI app plus cards and ATM withdrawals, needs no screen reading, and no app check can block it.
   - **Second:** read one in-app total, only in apps that let Friday in: Paytm Spend Summary, or Google Pay's monthly figure with the net-of-income caveat.
   - **Last resort:** add up history rows on screen.

## 1. Platform facts that decide this (Android 16, Friday as built)
- **How Friday is built** [primary: repo].
  - `android/app/src/main/res/xml/agent_service.xml` declares no `isAccessibilityTool`.
  - It sets the flags `flagRetrieveInteractiveWindows` and `flagReportViewIds`, plus `canRetrieveWindowContent` and `canPerformGestures`.
  - Approval prompts are drawn as a `TYPE_ACCESSIBILITY_OVERLAY` (`Overlay.kt`).
- **Any app can see Friday.** `getEnabledAccessibilityServiceList` needs no permission (research/02 §4.5) [primary].
- **Play Integrity's app-access-risk check** [primary: developer.android.com/google/play/integrity/app-access-risk, updated 2026-10-06].
  - It reports apps "running that … could be used to capture the screen, display overlays, or control the device".
  - Apps "not installed by Google Play" get the `UNKNOWN_` prefix.
  - The only exclusion is "verified accessibility services that have been through an enhanced Google Play accessibility review". That review means publishing on Play with `isAccessibilityTool`, or requesting one.
  - The calling app can show a `CLOSE_UNKNOWN_ACCESS_RISK` dialog that asks the user to close the detected apps.
  - So Friday should show up as `UNKNOWN_CAPTURING`/`UNKNOWN_CONTROLLING` whatever its manifest says [UNCERTAIN: our reading of the definitions].
  - Paytm is named as a Play Integrity user [primary: Android Developers blog, 2025-11-19]. No source says it uses this particular check.
- **The data-sensitive mark** [primary: AOSP 16 r1]. `View.calculateAccessibilityDataSensitive()` reads:
  ```java
  } else if (getFilterTouchesWhenObscured()) {
      // Views that set filterTouchesWhenObscured default to accessibilityDataSensitive.
      mInferredAccessibilityDataSensitive = ACCESSIBILITY_DATA_SENSITIVE_YES;
  } else if (mParent instanceof View && ((View) mParent).isAccessibilityDataSensitive()) {
      // Descendants of accessibilityDataSensitive Views are also accessibilityDataSensitive.
  ```
  - **Effects on a service that is not an accessibility tool, such as Friday:**
    - `includeForAccessibility(true)` leaves the view out of the tree.
    - `AccessibilityInteractionController.getRootView()` returns null when the window's root is sensitive, so the whole window comes back empty.
    - `AbstractAccessibilityServiceConnection` drops events from sensitive views.
  - **Why it matters here.** Apps commonly set `filterTouchesWhenObscured` to stop tapjacking. On Android 14+, an app that sets it on its root view disappears from Friday's view without "detecting" anything.
  - **Flutter.** Single virtual nodes can also carry the mark (`AccessibilityNodeInfo.setAccessibilityDataSensitive`, API 34). Flutter 3.35's `AccessibilityBridge` never sets it [primary: source grep]. So a Flutter app such as Google Pay can hide only its whole Flutter view, not single amounts.
- **FLAG_SECURE controls screen capture only.** It does not filter the accessibility tree (research/02 §4.4) [primary]. So Paytm's use of it does not settle whether Friday can read those screens. Data-sensitive marking and the app's own policy settle that. Where either one keeps Friday out, we stop.
- **Flutter turns on its accessibility tree for any service.** It calls `onAndroidAccessibilityEnabled()` whenever `AccessibilityManager.isEnabled()` is true, which any enabled service makes true [primary: flutter 3.35.0 `AccessibilityBridge.java`].
  - Nodes come out as `android.view.View`, or as `Button`, `EditText`, `ImageView` and similar.
  - Labels go in `contentDescription`, values in `text`.
- **Overlays.** Google Pay blocks payments and account management "while another app draws over the screen" [primary]. Whether it counts Friday's accessibility overlay is [UNCERTAIN]. Either way, Friday draws nothing over a payment app, which is simply the app's own rule.
- **India-specific Android protections.**
  - **Play Protect pilot (announced 2024-10-03).** Play Protect blocks installs from "Internet-sideloading sources" (browsers, messaging apps, file managers) of apps that declare SMS, notification or Accessibility permissions [primary]. adb is not named. Friday was installed over adb and runs (Phase 1–2d), so that path has not been blocked.
  - **Screen-share alert pilot (Nov 2025).** Android shows an alert when Google Pay, Navi or Paytm is opened while the user is screen-sharing on a call with an unknown contact [primary]. Friday does not screen-share, so this does not apply.

## 2. Per-app table
"Amounts readable via a11y" means readable **by Friday**, a service that is not an accessibility tool. TalkBack is a tool and is exempt from the data-sensitive filter. So screen-reader audits measure how well the app labels its screens, not what Friday will receive.

| App (package) | Detects / blocks a11y service? | FLAG_SECURE | Amounts readable via a11y (Friday)? | In-app spend summary? | Export? | Confidence |
|---|---|---|---|---|---|---|
| **Google Pay India** `com.google.android.apps.nbu.paisa.user` | No evidence for a11y as such. **Blocks** payments and account management while an app draws over the screen. **Blocks services** on "unsafe apps" or a modified OS (NPCI framework 2025) [primary] | No evidence | Unknown. Flutter tree, built for any service. Best-labelled of the big three UPI apps (2024 audit) [secondary] | **Yes, but net:** History shows expenses minus money received per month, Google Pay payments only [primary] | **Yes:** History → More → Get Statement (PDF) [primary] | Med (summary/export) · low (a11y) |
| **PhonePe** `com.phonepe.app` | No evidence. 2018 official stance: accessibility matters, relies on Android's own warnings [primary]. Device Guard (2025) checks for calls, not a11y [primary] | No evidence for the whole app. Guides tell users to screenshot History [secondary] | Unknown. Mostly native views [primary]. Worst-labelled of the three (2024) [secondary] | None found | **Yes:** History → Download Statement, sent by email [primary]. Format [UNCERTAIN] | Low |
| **Paytm** `net.one97.paytm` | No evidence for a11y. Refused to run with TeamViewer/AnyDesk installed (2020) [secondary]. Uses Play Integrity [primary] | **Yes, on some pages:** "UPI PIN and confidential transaction information" [primary, 2026] | Unknown. FLAG_SECURE does not hide the tree. Weak labels in audits [secondary]. Hidden payments are left out of the default passbook view [primary] | **Yes:** Spend Summary (Balance & History), Paytm UPI only [primary]. Monthly total shown first [secondary] | **Yes:** Download UPI Statement. PDF since 2024; Excel for some handles since 2025 [secondary] | Med |
| **CRED** `com.dreamplug.androidapp` | No evidence | No evidence | Unknown. Mix of native views and Flutter [secondary] | CRED Money (AA, 2024) shows spends across banks [secondary]. Monthly total unconfirmed | No evidence | Low |
| **Amazon Pay** (inside `in.amazon.mShop.android.shopping`) | No evidence | No evidence | Unknown. React Native in parts of the app [primary] | No evidence | No evidence | Low |
| **SBI YONO** `com.sbi.lotusintouch` | No evidence. Bound to the registered SIM [primary]. One report says it works with Developer options on [secondary] | No evidence | Unknown | No evidence | **Yes:** YONO "Manage e-statements" [primary]. OnlineSBI. `ESTMT` SMS returns an emailed PDF [primary] | Low |
| **HDFC Bank** `com.hdfcbank.android.now` (legacy `com.snapwork.hdfc` still updated late 2025) | No evidence for a11y. Conflicting reports of prompts to turn off USB debugging or Developer options [secondary] | No evidence | Unknown | "My Money" AA view ("Track spends across all accounts") [primary]. Where it sits in the app [UNCERTAIN] | **Yes:** in the app, a PDF with the Customer ID as password [primary]. NetBanking: PDF, Excel, Text, Delimited, MS Money [primary] | Low (a11y) · med (export) |
| **ICICI iMobile Pay** `com.csam.icici.bank.imobile` | **Refuses to run with Developer options or USB debugging on** [primary], so it is out on any phone with Developer options on (e.g. the adb test bench). a11y: no evidence | No evidence | Not reachable here | iFinance (AA) shows "Top Spending Categories" [primary]. Where it sits [UNCERTAIN] | Net banking e-statements, last 4 years [primary] | **High** (Developer-options block) · low (rest) |
| **BudgetBakers Wallet** `com.droid4you.application.wallet` | No evidence. Not a regulated payment app | No evidence | Likely good: probably native Kotlin (our inference from its careers page) [primary] | **Yes:** reports, cash flow, budgets [primary] | Imports CSV/XLS/OFX. No file export described. REST API and MCP server (Premium, cloud) [primary] | Med |

## 3. Per-app notes

### Google Pay India
- **Overlay block** [primary: support.google.com/pay/india/answer/10728104].
  - The app may show "Close any app that is displaying over your screen and retry".
  - The help page says you "can't use Google Pay to make transactions or manage cards and bank accounts while another app draws over the screen".
- **Device and app checks** [primary: answer/16886058].
  - The warnings are "Your device may not be secure" and "Google Pay has detected one or more apps on your device that may be unsafe".
  - Google Pay "only works on devices running an original, unmodified operating system". It cites the "National Payments Corporation of India (NPCI) Mobile Application Security Framework 2025".
  - The suggested fix is a Play Protect scan and uninstalling harmful apps.
  - **Open question.** Whether Google Pay counts an unknown accessibility service such as Friday as "unsafe" is [UNCERTAIN]. It is the most likely way Friday would hit a block here.
- **Screen sharing.** Users are told to close AnyDesk, TeamViewer and similar apps (answer/10768310) [primary]. Google Pay is a partner in the Nov 2025 screen-share alert pilot [primary].
- **History** [primary: answer/7430307].
  - The monthly amount is "your total expenses minus total amount received for every month".
  - History "only contains those made through Google Pay and not all UPI or banking transactions".
  - Date filters: This month, Last 30 days, Last 90 days.
- **Export** [primary]. See transaction history → More → **Get Statement** → choose a period → Continue to view and save it, or Share. The help page heading calls it a PDF or e-statement and does not mention a password.
- **"Ask Google Pay."** An opt-in Gemini chat in the Money tab that answers spending questions with charts. It is in limited rollout since Jul–Aug 2026 and runs in the cloud [secondary].
- **Accessibility audit.** Google Pay scored 93/115 (80.9%), the best of the three apps tested (Singh et al., *IEEE Access* 12, Jan 2024, doi 10.1109/ACCESS.2023.3348840; read through a 2026 summary) [secondary].

### PhonePe
- **Stated stance (Sep 2018 blog, after accessibility-malware reports).** "Accessibility is important for users with low-vision, seniors…". PhonePe relies on Android's own warnings and describes no detection of its own [primary].
- **Device Guard (Mar 2025).** "With user permission, leverages their device data", for example to notice the user is on a call and ask whether to continue [primary]. No accessibility check is described.
- **Screenshots.** No official statement. Third-party guides tell users to screenshot History entries, which suggests History is not FLAG_SECURE [secondary/UNCERTAIN].
- **UI.** "Mostly built with standard Android/iOS frameworks", plus the LiquidUI and WidgetX server-driven UI systems [primary: tech.phonepe.com, 2024]. That gives the best odds of a native tree.
- **Accessibility audit.** It scored 65/115 (56.5%), the worst of the three, with unlabelled navigation [secondary]. A 2022 audit flagged only the contrast on the "Paid to/Received" list [secondary].
- **No consumer spend-summary screen found.**
  - Its AA micro-app (launched Jun 2023) shows bank statements under Check Balance → "View Bank Statements", stored encrypted on the device [primary].
  - PhonePe gave up its AA licence in Feb 2025 [secondary], so whether that micro-app still exists is [UNCERTAIN].
- **Export** [primary; the help page now only renders with JavaScript]. History → Download Statement → choose months or a financial year. A verified email address is required, so the statement arrives by email. Format and password are [UNCERTAIN].

### Paytm
- **Remote-access check.** In Feb 2020 Paytm made users uninstall TeamViewer and AnyDesk [secondary, Digit]. Whether it still does is [UNCERTAIN]. Nothing found on accessibility services.
- **FLAG_SECURE** [primary, 2026-07-10]. "Sensitive pages, including UPI PIN and confidential transaction information, cannot be captured through screenshots or screen recordings." Whether the passbook counts as "confidential transaction information" is not stated.
- **Spend Summary** [primary, updated 2026-08-25]. Balance & History → Spend Summary. It is "based on your Paytm UPI payments" and shows "category-wise spending and monthly insights". One article says it "shows the total spends in the month upfront" [secondary, Digital Terminal, 2025-09-04].
- **Hide Payments.** Hidden entries drop out of the default passbook view until revealed with PIN or biometric [primary, as reported by our research agent]. Whether Spend Summary counts them is [UNCERTAIN]. **A sum of the passbook can silently miss hidden payments.**
- **Export.** Balance & History → Download UPI Statement → a date range or a financial year.
  - PDF since 2024-11-06 [secondary: Inc42].
  - Excel since 2025-03-11, only for the @ptaxis, @ptyes, @ptsbi and @pthdfc handles [secondary: Business Today].
- **UI.** The core looks native, judging by job ads [UNCERTAIN]. Mini Apps are "web applications … opened inside the Paytm … App" [primary], so their trees are WebView trees.
- **Accessibility audits (2022–24).** Login fields are read as one garbled string, and several elements have no accessible name [secondary].

### CRED
- **CRED Money.** Rolled out from 2024-07-25, it uses AA to show balances and transactions across banks and UPI, with budgets [secondary]. A single monthly total and any export are unconfirmed.
- **UI.** Native views plus Flutter. CRED publishes its NeoPOP UI kit for both [primary]; which screens use which is unknown.

### Amazon Pay
- **No standalone Android app was found.** Amazon Pay lives inside `in.amazon.mShop.android.shopping` ("Amazon India Shop, Pay, miniTV") [primary: Play listing]. A separate app was only "considered" (TechCrunch, 2024-08-19) [secondary].
- **UI.** Amazon has used React Native in Amazon Shopping since 2016 [primary: reactnative.dev showcase]. Nothing is specific to the Pay screens.
- **Spend summary and export.** None found.

### SBI YONO
- **Packages.** YONO is `com.sbi.lotusintouch`; YONO Lite is `com.sbi.SBIFreedomPlus` [secondary].
- **Device binding.** The app works only on a phone holding the registered SIM [primary, SBI 2021].
- **Developer options.** One report says the app works with them on [secondary].
- **Not found.** Anything on accessibility services, FLAG_SECURE or screen readers.
- **Statements.**
  - YONO lists "Manage e-statements" [primary].
  - OnlineSBI gives statements for a chosen date range [secondary].
  - From the registered mobile, send `ESTMT <account> <4-digit code>` by SMS to 09223588888. The statement arrives by email as a PDF, protected with that code [primary: onlinesbi quick links].

### HDFC Bank
- **Two apps.**
  - **New:** `com.hdfcbank.android.now` ("HDFC Bank App: Banking & Cards"), released Dec 2024 and rolled out in phases.
  - **Legacy:** `com.snapwork.hdfc`, still updated on 2025-10-13 [secondary: AppGoblin].
  - Which is live in 2026 is [UNCERTAIN].
- **Developer options / USB debugging.** Forum reports conflict: some users were asked to turn off USB debugging, others say the app works [secondary].
- **Statements.**
  - **In the app:** Statement → Get Statement → a preset or custom range → a PDF protected with the Customer ID [primary].
  - **NetBanking:** PDF, Excel, Text, Delimited or MS Money [primary: FAQ].
- **My Money.** An AA view of "all Bank accounts and Equities" ("Track spends across all accounts"; data as of the previous working day) [primary]. Where it sits in the app is [UNCERTAIN].

### ICICI iMobile Pay
- **Refuses to run with Developer options or USB debugging on** [primary].
  - icici.bank.in/developer-option: "Dear customer, you have enabled Developer option in your phone, Please disable Developer Option to enjoy services of ICICI Bank's iMobile app."
  - icici.bank.in/usb-debugging: "You have enabled USB debugging on your phone. Please disable USB debugging to enjoy Mobile Banking services."
- **Consequence for the test bench.** It keeps both on for adb (D-007), so iMobile can't run there, with or without Friday. Turning them off is what ICICI asks; whether to do that is the owner's call.
- **Accessibility services.** No evidence either way.
- **Remote-access apps.** ICICI tells users never to install Screenshare, AnyDesk or TeamViewer when asked to [primary].
- **Statements.** Net banking e-statements cover the last 4 years [primary]. iFinance (AA) shows other banks' accounts and "Top Spending Categories" [primary].

### Evidence from other Indian banks (not in scope, but shows the pattern)
- **HSBC India's FAQ on its in-app security SDK** [primary: hsbc.bank.in/help/faqs/rasp, undated].
  - "If our app detects accessibility permissions that are incompatible with its safeguards", it shows an alert and blocks use.
  - It tells users to "Turn off accessibility permissions for all apps" and adds "On some devices, you may also need to turn off screen reading".
  - "The app will suspend access if developer mode is on."
  - "Relaunching the app restores access" once the cause is gone.
- **IndusInd "Indie" (May 2025).** The app showed "untrusted screen reader detected" with a single OK button that closed it, which locked out blind TalkBack users [secondary]. An activist blamed a misconfigured OneSpan App Shield, an in-app security SDK whose feature list includes "Anti-Screen Reader" [secondary].
- **Lesson.** A security-SDK update can start blocking without notice. Read "no evidence" as "untested", not as "allowed".

### BudgetBakers Wallet
- **Not a regulated payment app.** Nothing found on blocking, FLAG_SECURE or an app lock.
- **UI.** Its careers page lists "Go, Kotlin, React TypeScript, Swift…", so the Android app is likely native Kotlin (our inference) [primary].
- **Reports.** "Visual reports and predictive analytics", cash-flow insights and budgets [primary].
- **Import.** "Standard CSV, XLS, or OFX" [primary].
- **Bank sync.** "Over 15,000 banks", Premium only. India is not mentioned [primary].
- **Export.** No file export is described [primary].
  - A REST API exists for Premium users: a personal token from the web app, 300 requests per hour, read and write access.
  - An MCP server also exists.
  - Both run on BudgetBakers' servers (rest.budgetbakers.com), so they break "everything local".

## 4. What drives this: regulation, and the pressure to stay accessible
- **RBI Master Direction on Digital Payment Security Controls** (RBI/2020-21/74, 18 Feb 2021). Chapter IV covers mobile apps [primary]:
  - 56(1): "Device policy enforcement (allowing app installation/ execution after baseline requirements are met)".
  - 56(8): "Ability to identify remote access applications (to the extent possible) and prohibit login access".
  - 58: "REs may explore the feasibility of implementing a code that checks if the device is rooted/ jailbroken".
  - 60: "REs shall ensure device binding of mobile application".
  - 65: "REs may consider designing anti-malware capabilities into their mobile applications".
  - **No clause covers screenshots, overlays or accessibility.** Those are the vendor's or bank's choice. Remote-control malware works through accessibility, so in-app security SDKs block accessibility services as their way of meeting 56(8).
- **NPCI Mobile Application Security Framework 2025.**
  - Google Pay names it as the basis of its device and app checks [primary]. The Indian government has also referred to it, alongside "CUISF 2025", in a reply in parliament [secondary: Lok Sabha reply via TaxTMI, 2026-07-20].
  - Its text is not public. Reportedly it requires root, tamper and hook detection, plus hardware-backed device and SIM binding [secondary/UNCERTAIN].
  - The UPI PIN is entered only in NPCI's Common Library, which runs only on a device bound to the SIM and the app [secondary]. Friday never goes near it.
- **RBI's 2025 directions on how digital payments are authenticated** (issued 2025-09-25, in force 2026-04-01). Risk-based checks may use device signals [secondary]. Nothing on accessibility.
- **Malware history.** CERT-In's advisory on SOVA (Sep 2022) covered a banking trojan aimed at Indian bank apps. SOVA reportedly worked through the accessibility service [secondary]. Drinik (2021) did *not* use accessibility.
- **The pull the other way: apps must stay usable by people with disabilities.**
  - The RPwD Act 2016, and the Supreme Court's ruling in *Pragya Prasun v. Union of India* (30 Apr 2025), require accessible digital KYC and services. RBI followed up in Aug 2025 with mandatory accessibility audits [secondary].
  - So apps must keep screen readers working while blocking "untrusted" services. Play Integrity's exclusion of Play-verified `isAccessibilityTool` services is the sanctioned way to tell the two apart [primary].
  - Friday sits on the untrusted side by design (D-014). That is the boundary we keep.

## 5. Frameworks (Q2)
| App | Framework | Evidence |
|---|---|---|
| Google Pay India | **Flutter**: a "clean rewrite" with "critical native plugins for payments in India", open beta in India and Singapore | [primary] Google Developers Blog, 2020-09-18. [secondary] Android Police, 2020-09-21: the India beta is "now based on Flutter". Still Flutter in 2026: [UNCERTAIN] |
| PhonePe | **Mostly native** Android, plus its own server-driven UI (LiquidUI, about 130 screens; WidgetX). React Native only for early partner micro-apps, around 2018 | [primary] tech.phonepe.com, 2024-07 and 2024-09. The React Native part is [UNCERTAIN] |
| Paytm | Native core [UNCERTAIN, from job ads]. Mini Apps are web pages inside the app [primary]. Some "hybrid" web sections [secondary] | No engineering source found for the consumer app |
| CRED | Native views plus Flutter | [primary] NeoPOP published for both. [secondary] Job ad |
| Amazon Shopping (Pay) | React Native in parts, since 2016 | [primary] reactnative.dev/showcase |
| SBI, HDFC, ICICI | Not found | — |
| BudgetBakers Wallet | Native Kotlin (our inference) | [primary] Careers page |

**What this means for the accessibility tree.**
- **Native and React Native apps** produce real Android views, so amounts arrive as `TextView` nodes.
- **Flutter apps** produce semantics nodes with generic class names. Laya-Android was trained on native AndroidControl trees, so Flutter screens are outside what it was trained on (research/02, 03).
- **Paytm's Mini Apps** produce WebView trees.
- **Reading one number** works with any of these, as long as the node carries the text. **Adding up rows** is dominated by scrolling through long lists that only render what is on screen, whatever the framework.

## 6. Official paths that avoid screen reading (Q4)

### 6.1 Account Aggregator: closed to a personal app
- **How it works** [primary: RBI Master Direction on NBFC-AAs; Sahamati FAQ]. The receiving app (the FIU) asks for consent. The customer approves it in an AA app. The bank (the FIP) then sends the data encrypted end to end, and the AA never sees or stores it.
- **What a fetch returns** [primary: ReBIT deposit schema]. For each transaction: `type` (CREDIT/DEBIT), `mode` (CASH/ATM/CARD/UPI/FT/OTHERS), `amount`, `currentBalance`, `transactionTimestamp`, `valueDate`, `narration`, `reference`.
- **Friday cannot be an FIU.**
  - An FIU "means an entity registered with and regulated by any financial sector regulator" (RBI Master Direction, clause 3(1)(xii)) [primary].
  - Sahamati: "you cannot be an FIU or FIP if you are not a regulated entity" [primary].
  - The developer sandboxes serve mock data only [primary].
- **The owner's own use** [primary: Sahamati template CT019, 8 Apr 2025].
  - The stated purpose is "To view your financial position on AA". Bank deposits go back 13 months.
  - The data lives for 7 days and "should be stored on Customer's Device".
  - Two-factor login and SIM binding are required first.
  - Downloads must be password-protected and shared by hand only. **"Auto-fetch and auto-sharing are prohibited."**
  - **So Friday must not drive an AA app to pull data.** At most, the owner downloads a file by hand.
  - OneMoney's "Self Data Download" (v1.0.5, 2024-03-18) [primary: release notes] would give one file across banks. Whether it covers bank accounts, and in what format, is [UNCERTAIN].
- **Consumer apps that use AA.** CRED Money, HDFC My Money, ICICI iFinance ("Download Statements"), INDmoney, and Fold (CSV from its web app at view.money) [primary/secondary].
  - All of them route the data through the company's own servers.
  - Reading them on screen raises the same questions as §2.
  - Fold's CSV is a file, but the data has passed through Fold first.

### 6.2 Bank statements: the strongest path
| Bank | Where | Formats | Range | Password |
|---|---|---|---|---|
| HDFC | App: Statement → Get Statement. NetBanking: Account Statements | App: PDF. NetBanking: PDF, Excel, Text, Delimited, MS Money; emailed copies are PDF only [primary] | Preset or custom; maximum not stated | Customer ID [primary] |
| ICICI | Net Banking → Bank Accounts → e-Statements; or subscribe by email | PDF or XLS [secondary] | Last 4 years [primary] | Set by the bank; sources conflict [UNCERTAIN] |
| SBI | OnlineSBI account statement; YONO "Manage e-statements"; `ESTMT` SMS | PDF or Excel [secondary]; the SMS route sends a PDF by email [primary] | Maximum not confirmed | SMS route: your own 4-digit code [primary] |

- **Coverage.** The bank statement is the full ledger. It covers UPI payments from every app (Google Pay, PhonePe, Paytm, CRED and the rest), plus cards, ATM withdrawals and transfers. The narration field carries the UPI details.
- **Parsing on the phone.**
  - Delimited, Excel and CSV files are easy.
  - For PDFs, Android 15's `PdfRenderer` can open password-protected files, search their text, and select and copy it [primary: Android 15 features page]. Whether that is enough to pull out statement tables is [UNCERTAIN until tried].
  - Passwords stay on the phone and never go into `captures/` (D-004).

### 6.3 UPI-app statements and other routes
- **Google Pay.** A PDF from *Get Statement*, covering Google Pay payments only [primary]. Google Takeout can "export your Google Pay data" [primary], but whether Indian UPI history is included is [UNCERTAIN, untested].
- **Paytm and PhonePe.** Paytm gives a PDF, or Excel for some handles [secondary]. PhonePe sends its statement by email [primary]. Each covers only its own app, but each adds payee names that the bank's UPI narration may lack.
- **BHIM 3.0** (NPCI's own UPI app, 2025-03-25). It has a monthly spending dashboard with categories, but no export was found [secondary].
- **NPCI.** There is no consumer service for UPI transaction history. "UPI Help" covers only payment status, complaints and standing mandates [secondary].
- **DPDP Act, section 11.** It gives a right to "a summary of personal data", not a file. The Rules were notified on 2025-11-13, and section 11 takes effect on 2027-05-13 [secondary].
- **Bank SMS and email alerts.** These are what Indian expense trackers usually read. That route is parked together with notification and SMS monitoring (D-018).

## 7. What this means for Friday (proposed)
1. **First choice: a statement file, parsed on the phone.**
   - The owner taps the export once: a bank statement, or an app's Get Statement. Friday parses the file in Downloads and adds up the debits.
   - It needs no screen reading, no app policy stands in the way, and it covers every payment app.
   - Cost: a parser for PDF, XLS and CSV files. It is not built yet.
2. **Second choice: read one on-screen total, and only where the app lets Friday in.**
   - **Paytm Spend Summary** covers Paytm UPI only. Watch for hidden payments.
   - **Google Pay's monthly figure** is net of money received and covers Google Pay only. Report it as "net Google Pay outflow", never as "expenses".
   - Getting there takes 2–3 taps, so the long-candidate-list risk (README risk 1) stays small.
3. **Last resort: add up history rows on screen.** It means scrolling a list that renders only what is visible, with long candidate lists, Flutter labels and hidden entries. And it still covers one app only.
4. **Rules for any session in a payment app.**
   - Navigate only to targets on an **allowlist of read-only screens**: History, Balance & History, Spend Summary, Get Statement. The risky-word deny-list from D-011 is not enough here.
   - Never tap Pay, Send, Scan or Check balance. Check balance opens the UPI PIN prompt.
   - Draw no overlay while a payment app is on screen.
   - At the first warning or block, stop and record it (D-014).
   - Never automate an AA app (CT019).
   - Never declare `isAccessibilityTool` to get onto an app's allowlist. That crosses D-014's line, and Play Integrity would not treat Friday differently anyway.
5. **Define "expenses" before building anything.** For example: debits in the calendar month, minus self-transfers and refunds. Every in-app figure we found uses a different definition.

## 8. Owner-run behaviour check (turns the [UNCERTAIN] cells into [measured])
This check only observes the apps.
- **Setup.** Friday's service is enabled as usual. The owner opens each app and navigates by hand. Friday takes **no actions** and draws no overlay. It only exports the tree of the screen in front of it (D-015).
- **Record, for each app:**
  1. Any warning or block text, at launch, at login, and on the History or summary screen.
  2. Whether the exported tree contains the amounts (₹ strings, the monthly figure), or whether the window is empty or partial.
  3. Any side effect, such as a logout or a request to re-register the device.
- **Stop rule.** At the first warning or block, close the app, note the exact text, and mark the app out of scope. Don't retry, and don't change any settings to get in.
- **Order.** Wallet → Paytm → Google Pay → PhonePe → CRED → Amazon → SBI → HDFC. Skip ICICI while Developer options are on.
- **Storage.** Trees stay in the git-ignored `captures/` folder (D-004). This note records only yes/no answers and the warning texts.

## 9. Dead ends
- **No accessibility warnings found.** No quoted warning text about accessibility services for any of the 8 apps. No statement about allowing TalkBack. No account consequences (logout, UPI deregistration). The worst outcome reported anywhere is that the app refuses to run until the cause is removed.
- **Forums and reviews unreadable.** Reddit was blocked for both search and fetch, and Play Store review text did not render. XDA, TechEnclave and the issue trackers of Tasker/AutoInput, MacroDroid and Key Mapper had nothing on these apps.
- **No TalkBack tests of amounts.** No source says whether amounts or history are spoken in any of the apps. Neither audit covers it: Singh et al. 2024, and the Vidhi/I-Stem/Mission Accessibility index of Jan 2023.
- **No sign of the protective flags.** No public sign that any of these apps sets `accessibilityDataSensitive` or `filterTouchesWhenObscured`, or uses Play Integrity's app-access-risk check.
- **FLAG_SECURE unknown for most apps.** Nothing for Google Pay, CRED, Amazon, SBI, HDFC or ICICI, nor any official statement from PhonePe. Nothing on whether NPCI's PIN library sets it.
- **NPCI framework text not found.** The Mobile Application Security Framework 2025 is not public, and no NPCI circular names accessibility-service fraud.
- **No framework evidence beyond §5.** APK-analysis sources (AppBrain, LibChecker, Exodus) gave no native-library listings. There is no engineering source for Paytm's consumer app, nothing on the bank apps' frameworks, and nothing confirming Google Pay is still Flutter in 2023–26.
- **Missing features.** No spend summary for PhonePe, Amazon Pay, YONO or iMobile. No export for CRED or Amazon Pay. No export in BHIM.
- **Account Aggregator.** No route for a personal app, no sandbox with live data, and CT019 bans automatic fetching. PhonePe gave up its AA licence.
- **Android docs page too large.** The developer.android.com `View` reference page could not be fetched whole, so the AOSP source was read instead.

## 10. Open / next (for the orchestrator)
- **README and app.md wording.** research/README §4 and docs/app.md §Safety state as fact that "Banking and UPI apps detect accessibility services and refuse to run". Suggested wording: "expected (in-app security SDKs; RBI clause 56(8)), but unverified for the named apps; check each app (research/11 §8) and stop where blocked." These files were not edited, to stay within this task's scope.
- **Decisions to log (Proposed).**
  - Expense questions use a statement file first, a single in-app total second, and adding up rows last.
  - Payment-app sessions follow the allowlist and no-overlay rules in §7.4.
  - Friday never automates an AA app.
  - docs/DECISIONS.md is the orchestrator's to edit.
- **README's Files table.** Add this note (11). Notes 08, 09 and 10 are also missing; 09 and 10 appeared from other sessions while this one ran.
- **Still unverified.**
  - What Google Takeout contains for India.
  - PhonePe's statement format and password.
  - SBI's and ICICI's statement formats and passwords.
  - Whether Google Pay counts Friday as an "unsafe app".
  - Whether `PdfRenderer` text extraction can handle statement tables.

## Sources
**Platform**
- AOSP `android-16.0.0_r1` source:
  - `core/java/android/view/View.java`
  - `core/java/android/view/AccessibilityInteractionController.java`
  - `core/java/android/view/accessibility/AccessibilityNodeInfo.java`
  - `services/accessibility/.../AbstractAccessibilityServiceConnection.java`
  - Browse from https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/
- Flutter 3.35.0 `AccessibilityBridge.java`: https://github.com/flutter/flutter/blob/3.35.0/engine/src/flutter/shell/platform/android/io/flutter/view/AccessibilityBridge.java
- Play Integrity, app access risk: https://developer.android.com/google/play/integrity/app-access-risk
- Paytm named as a Play Integrity user: https://developer.android.com/blog/posts/stronger-threat-detection-simpler-integration-protect-your-growth-with-the-play-integrity-api
- Android 15 PDF features: https://developer.android.com/about/versions/15/features
- Play Protect India pilot: https://blog.google/intl/en-in/products/launching-enhanced-fraud-protection-pilot-in-india/
- Screen-share alert pilot (Nov 2025): https://blog.google/intl/en-in/company-news/protecting-vulnerable-audiences-is-at-the-heart-of-ai-safety-efforts/
- TechCrunch on the same pilot: https://techcrunch.com/2025/11/20/google-steps-up-ai-scam-protection-in-india-but-gaps-remain

**Google Pay**
- Help pages (support.google.com/pay/india/answer/…):
  - Overlays: https://support.google.com/pay/india/answer/10728104
  - Security alerts: https://support.google.com/pay/india/answer/16886058
  - Screen sharing: https://support.google.com/pay/india/answer/10768310
  - History and statement: https://support.google.com/pay/india/answer/7430307
- Takeout: https://support.google.com/googlepay/answer/9015738
- Flutter rewrite: https://developers.googleblog.com/en/google-pay-picks-flutter-to-drive-its-global-product-development/
- Android Police on the India beta: https://www.androidpolice.com/2020/09/21/google-pay-india-picks-up-a-tweaked-design-in-beta-with-flutter-rewrite/
- Ask Google Pay: https://www.tech2.com/apps/google-pay-gets-a-gemini-powered-chatbot-for-spending-insights-in-india-text-10240689.html

**PhonePe**
- 2018 blog on malicious apps: https://www.phonepe.com/blog/trust-and-safety/fraud-alert-preventing-malicious-apps-from-accessing-your-data/
- Device Guard: https://www.phonepe.com/blog/trust-and-safety/phonepes-guardrails-advanced-risk-detection-features/
- App platform: https://tech.phonepe.com/introducing-the-phonepe-app-platform/
- LiquidUI: https://tech.phonepe.com/introducing-liquidui-phonepes-server-driven-ui-framework/
- AA launch: https://www.phonepe.com/press/phonepe-group-launches-account-aggregator-services/
- Statement help: https://cms.phonepe.com/en/myhelp/bank-accounts-bhim-upi-debitcredit-cards/transaction-statement/how-do-i-get-statement-my-payments-phonepe

**Paytm**
- Account security (FLAG_SECURE statement): https://paytm.com/support/upi/securing-your-paytm-account-kyc-otp-safety
- Spend Summary:
  - https://paytm.com/blog/payments/wondering-where-your-salary-disappeared-check-paytm-spend-summary/
  - https://paytm.com/blog/payment-gateway/paytm-spend-summary-a-simple-way-to-understand-your-spending/
- Statement downloads:
  - Inc42 (PDF): https://inc42.com/buzz/paytm-introduces-upi-statement-download-option
  - Business Today (Excel): https://www.businesstoday.in/amp/personal-finance/news/story/paytm-expands-upi-service-with-excel-statement-downloads-for-tax-expense-management-check-how-it-works-467605-2025-03-11
- Digital Terminal (monthly total): https://digitalterminal.in/amp/story/solutions/paytm-app-update-offers-customizable-spend-tags-and-smarter-financial-insights
- Digit (TeamViewer/AnyDesk): https://www.digit.in/news/apps/paytm-is-forcing-users-to-uninstall-teamviewer-anydesk-apps-to-prevent-frauds-52188.html
- Mini Apps: https://www.paytmpayments.com/docs/miniapps/overview

**CRED and Amazon**
- CRED Money launch: https://techcrunch.com/2024/07/24/cred-launches-personal-finance-manager-for-indias-affluent/
- NeoPOP: https://github.com/CRED-CLUB/neopop-android, https://github.com/CRED-CLUB/neopop-flutter
- React Native showcase: https://reactnative.dev/showcase

**Banks**
- ICICI:
  - https://www.icici.bank.in/developer-option
  - https://www.icici.bank.in/usb-debugging
  - https://www.icicibank.com/online-safe-banking/mobile-banking
  - https://www.icici.bank.in/personal-banking/accounts/savings-account/statements
- HDFC:
  - https://www.hdfc.bank.in/blogs/digital-banking/view-download-account-statement-hdfc-bank-app
  - https://www.hdfc.bank.in/savings-account/my-money
  - https://appgoblin.info/apps/com.hdfcbank.android.now
- SBI:
  - https://sbi.bank.in/web/yono/account-related
  - https://onlinesbi.sbi.bank.in/sbf_quick.html
- HSBC India: https://www.hsbc.bank.in/help/faqs/rasp/

**Regulation and accessibility**
- RBI Master Direction on Digital Payment Security Controls: https://www.rbi.org.in/Scripts/BS_ViewMasDirections.aspx?id=12032
- RBI Master Direction on NBFC-AAs: https://www.rbi.org.in/Scripts/BS_ViewMasDirections.aspx?id=10598
- Lok Sabha reply (NPCI framework): https://www.taxtmi.com/news?id=73906
- Vidhi accessibility index: https://vidhilegalpolicy.in/wp-content/uploads/2023/01/REPORT-BOOK-TAGGED.pdf
- Summary of the UPI app audit: https://www.ijraset.com/best-journal/accessibility-challenges-in-upibased-mobile-payment-applications

**Account Aggregator and Wallet**
- Sahamati:
  - FAQ: https://sahamati.org.in/faq/
  - Template CT019: https://sahamati.org.in/aa-fair-use-template-library/ct019-self-use-consent-on-aa-apps/
  - Sandbox onboarding: https://developer.sahamati.org.in/sahamatinet-mvp/integration-steps/sandbox-onboarding
- ReBIT deposit schema: https://specifications.rebit.org.in/api_schema/account_aggregator/documentation/deposit.html
- Setu consent object: https://docs.setu.co/data/account-aggregator/consent-object
- Fold FAQ: https://docs.fold.money/faq
- BudgetBakers:
  - Wallet: https://budgetbakers.com/en/products/wallet/
  - REST API: https://budgetbakers.com/en/products/wallet/integrations/rest-api/
  - MCP server: https://budgetbakers.com/en/products/wallet/integrations/mcp/
  - Careers: https://budgetbakers.com/en/careers/positions/
