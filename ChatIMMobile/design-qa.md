# ChatIM 登录流程 Design QA

## Evidence

- Source visual truth: `C:\Users\ASUS\.codex\generated_images\01a0b56f-d6f3-73e2-97f2-7c5f66afe4bd\exec-a9c76d82-db05-442e-913b-5a292d0a7515.png`
- Source pixels: 853 × 1855 px. It was normalized proportionally to about 392 × 852 px for comparison with the app viewport.
- Implementation URL and screenshot source: `http://localhost:4173/?demo=1`, captured from Codex in-app Browser tab 3. The browser surface retained the screenshot in the task but did not expose an on-disk screenshot path.
- Browser QA viewport: 1400 × 1200 px for the fidelity pass.
- App screen: verified by DOM measurement at 393 × 852 CSS px, scale 1, browser density 1.
- State: iPhone, light theme, password login, demo credentials prefilled, keyboard closed.
- Full-view evidence: the source visual and the 1:1 browser-rendered login screen were opened and inspected during the same QA pass. Both use a warm lifestyle collage, a high-contrast brand message, a white rounded authentication sheet, two login modes, restrained green actions, and warm neutral surfaces.
- Focused region evidence: the authentication sheet was checked separately for tab selection, field borders, password reveal affordance, forgot-password action, primary action, registration prompt, copy wrapping, and bottom safe-area clearance. A separate crop was not needed because these details were readable at the verified 1:1 app size.

## Findings

- No actionable P0, P1, or P2 visual differences remain.
- [P3] The source uses a larger editorial logo and handwritten phrase in the collage, while the implementation uses a compact app mark and editable product headline. The implementation preserves the source hierarchy and warmth while keeping the authentication controls readable on a 393 × 852 screen.
- [P3] The implementation adds a short welcome heading, field icons, an explicit demo badge, and legal copy. These are deliberate product requirements and remain visually subordinate to the login action.

## Required Fidelity Surfaces

- Fonts and typography: the app uses the native Chinese UI stack (`PingFang SC`, `Microsoft YaHei`, `Noto Sans CJK SC`, system UI) with 400–700 optical weights. Headline wrapping, tab weight, field text, small legal text, and letter spacing were checked at 1:1 size.
- Spacing and layout rhythm: the image-to-sheet transition, 30 px sheet radius, 25 px horizontal padding, 13–14 px controls, aligned field rows, and safe-area spacing match the source's soft mobile rhythm. All persistent controls remain visible.
- Colors and visual tokens: warm ivory `#fbf8f1`, paper `#fffefa`, green `#2f8359`, dark green `#226b48`, terracotta `#b86447`, and low-contrast neutral borders reproduce the source palette with sufficient foreground contrast.
- Image quality and asset fidelity: `public/app-assets/login-connection-collage.png` is a dedicated raster asset with the same coffee, phone, travel, and human-connection art direction. It stays sharp at the target crop and is not replaced with CSS art or placeholder geometry.
- Copy and content: login modes, email/password/code fields, forgot-password fallback, registration prompt, protocol copy, loading states, and error/success messages are complete and use concise Chinese product language.

## Interaction and Runtime Checks

- Password login succeeded with demo credentials.
- Captcha sending showed a 60-second disabled countdown and success message.
- Code login succeeded.
- Registration succeeded after nickname entry and captcha sending.
- Session restoration after reload succeeded.
- Profile navigation and logout returned to the login screen.
- The final clean browser tab reported no console warnings or errors.
- `npm run check:runtime` passed for all 28 protected runtime files.
- `npm run build` passed.

### Round 2: signed-in shell and chat

- Playwright verified the conversation list, unread clearing, search filtering, contact-to-chat flow, text sending, and tab navigation.
- Drafts, new conversations, and message caches were verified after a full page reload.
- The chat composer stays immediately above the simulated keyboard on iPhone, and the message viewport remains visible while typing.
- The main tab bar remains reachable while search input is focused and dismisses the keyboard when switching sections.
- iPhone and Pixel 10 screenshots were inspected for safe-area clearance, list density, bottom navigation, and profile layout.
- One P1 keyboard-layout issue was found during the pass: the chat message viewport initially subtracted the keyboard height twice. It now leaves keyboard sizing to `MobileScroll`, and the corrected screenshot shows all messages above the composer.

### Round 3: friend search and applications

- Playwright verified the complete demo flow: open add-friend search, reject invalid input, find a user by email, inspect the profile, send a custom friend request, and show the success state.
- The new-friends page was checked with unread, read, accepted, rejected, and expired states. Accepting a request adds the person to contacts and opens a new empty chat; rejecting a request removes its actions; marking all as read updates the badge.
- A separate real-mode browser pass intercepted the backend routes and verified friend-list, application-list, unread-count, and user-search requests. Each authenticated request carried both `access-token` and `refresh-token` headers.
- The final demo and real-mode passes reported no browser console errors or warnings.
- Evidence screenshots: `output/playwright/friend-flow/friend-profile.png` and `output/playwright/friend-flow/friend-applications.png`.

## Comparison History

1. Initial browser pass found a P2 layout issue: browser focus scrolling moved the framed device itself after authentication, which shifted the main page upward and left the simulated keyboard visible in the clipped frame.
2. Fixed by locking the device frame with `overflow: clip` while leaving the app's own `MobileScroll` regions functional.
3. Post-fix browser evidence showed the main header, welcome card, empty state, bottom navigation, profile page, and login page aligned inside the full 393 × 852 screen with no keyboard residue or hidden persistent controls.
4. The final clean-tab pass rechecked the login screen after the font-bundle optimization and found no new P0/P1/P2 issue.
5. Playwright keyboard screenshots found that focused authentication fields could sit behind the simulated keyboard. The focus handler now converts viewport distance to the scaled phone's scroll units and keeps the active field above the keyboard; revised screenshots and bounding-box measurements confirmed the fix.

## Implementation Checklist

- [x] Match the selected warm lifestyle direction.
- [x] Keep authentication actions visible and readable on the target iPhone viewport.
- [x] Verify password, code, registration, restore, and logout states.
- [x] Verify safe areas, outer-frame scroll lock, and keyboard dismissal.
- [x] Verify a clean production build and a clean browser console.

## Follow-up Polish

- A future brand pass can replace the temporary `C` app mark with the final production logo asset once the logo system is defined.

final result: passed
