# Agent: 100 Improvement Roadmap

This is a staged engineering backlog, not a claim that all 100 items are implemented. The current priority is to make project discovery, direct editing, recovery and APK rebuilding reliable before adding advanced automation.

## A. Project discovery and indexing
1. [x] Inspect the selected decoded APK root and report actual directories.
2. [x] Rank likely GUI files from real project paths.
3. [x] Identify the MAIN/LAUNCHER Activity from AndroidManifest.xml and match its Smali class.
4. [ ] Build a persistent file index with path, type, size and modification time.
5. [ ] Detect all dex roots: smali, smali_classes2, smali_classes3 and later.
6. [ ] Detect resource qualifiers such as layout-land, layout-night, values-v31 and drawable-hdpi.
7. [ ] Detect whether the APK was decoded with resources, code, or both.
8. [ ] Identify obfuscated classes and map manifest components to their Smali files.
9. [ ] Map layout resource IDs to Activity references.
10. [ ] Show a visual project tree with file counts and scan progress.

## B. Code and resource understanding
11. [ ] Parse AndroidManifest.xml into a component/permission summary.
12. [ ] Map Activity, Service, Receiver and Provider declarations to source files.
13. [ ] Detect Compose, XML Views, WebView and custom Canvas UI patterns.
14. [ ] Trace setContentView and LayoutInflater references to layout resources.
15. [ ] Trace R.color, R.string, R.drawable and R.layout references.
16. [ ] Explain a Smali method in readable pseudocode before changing it.
17. [ ] Detect string resources and where they are used.
18. [ ] Identify shared styles, themes, color selectors and shape drawables.
19. [ ] Detect custom fonts, icons, animations and vector drawables.
20. [ ] Build a dependency map showing which screens use each shared resource.

## C. Natural-language editing
21. [ ] Convert requests such as “make the main screen red” into a file-level edit plan.
22. [ ] Distinguish global theme changes from one-screen changes.
23. [ ] Prefer shared theme tokens when a color is used across the app.
24. [ ] Support exact text, XML attribute, resource value and Smali patches.
25. [x] Support transactional multi-file patches with pre-validation.
26. [ ] Generate a patch plan before applying complex UI redesigns.
27. [ ] Apply consistent spacing, typography, color and component rules across screens.
28. [ ] Add new XML layouts and resource files when necessary.
29. [ ] Rename resources safely while updating all references.
30. [ ] Preserve app behavior when making visual-only changes.

## D. File editing and patch quality
31. [x] Read large files in bounded line chunks.
32. [x] Return recoverable errors when a guessed folder is missing.
33. [ ] Add fuzzy path suggestions for misspelled file names.
34. [ ] Detect file changes made outside the Agent after it read a file.
35. [ ] Verify patch context and uniqueness before every write.
36. [ ] Show unified diffs with line numbers.
37. [ ] Support multiple independent hunks in one file.
38. [ ] Support patch transactions spanning up to a configurable number of files.
39. [ ] Preserve line endings and file encoding.
40. [ ] Detect malformed XML before writing it to the project.

## E. Safety, history and rollback
41. [x] Back up files before editing.
42. [x] Roll back files changed during the current Agent session.
43. [ ] Add named checkpoints before each task.
44. [ ] Add undo/redo history across app restarts.
45. [ ] Show a complete changed-file list before build.
46. [ ] Require approval for manifest and destructive operations.
47. [ ] Protect signing keys, API keys and private configuration from file reads.
48. [ ] Prevent writes outside the selected decoded project.
49. [ ] Detect prompt-injection instructions embedded in project files and treat them as untrusted data.
50. [ ] Add a “restore original project” option with an explicit confirmation.

## F. Build and repair
51. [x] Build the selected decoded project with the integrated Apktool engine.
52. [x] Return build errors and bounded build logs to the Agent.
53. [ ] Parse resource compiler errors into file/line/actionable diagnostics.
54. [ ] Parse Smali assembler errors and identify the failing method.
55. [ ] Detect missing framework resources and explain the required framework.
56. [ ] Validate resource references before starting the full build.
57. [ ] Detect duplicate resource names and invalid XML attributes.
58. [ ] Retry a failed build only after a concrete diagnostic-driven fix.
59. [ ] Keep each build output associated with its task checkpoint.
60. [ ] Export the rebuilt APK through Android's Storage Access Framework.

## G. Testing and verification
61. [ ] Verify the generated APK exists and has a non-zero size.
62. [ ] Run APK structure and ZIP integrity checks after build.
63. [ ] Compare original and rebuilt manifests.
64. [ ] Detect whether the APK is signed, unsigned or signature-incompatible.
65. [ ] Add an explicit user-approved install action.
66. [ ] Capture launch/crash logs after installation where device access permits.
67. [ ] Add a smoke-test checklist per changed screen.
68. [ ] Compare before/after screenshots on the device.
69. [ ] Detect missing resources and runtime resource lookup failures.
70. [ ] Produce a final verification report separating built, installed and runtime-tested states.

## H. GUI design and preview
71. [ ] Add an XML layout preview for supported Android view layouts.
72. [ ] Preview color and theme changes before applying.
73. [ ] Offer light and dark theme variants.
74. [ ] Generate accessible color contrast suggestions.
75. [ ] Detect touch targets smaller than recommended sizes.
76. [ ] Suggest consistent spacing and typography tokens.
77. [ ] Add a screen hierarchy view for nested layouts.
78. [ ] Preview drawable shapes, selectors and vector assets.
79. [ ] Compare layout variants across screen sizes and orientations.
80. [ ] Add a design consistency audit across all discovered screens.

## I. Agent experience and reliability
81. [x] Keep the Agent GUI and saved API profiles.
82. [x] Display project scan and GUI discovery events in the Agent activity feed.
83. [ ] Show the current file being inspected or edited.
84. [ ] Add task cancellation that stops new tool calls safely.
85. [ ] Persist task state if Android kills the Agent Activity.
86. [ ] Add a resumable task queue.
87. [ ] Show tool-call input/output in a collapsible diagnostic panel.
88. [ ] Let users switch between plan, execution, diff and build tabs.
89. [ ] Add plain-language explanations for each failed step.
90. [ ] Add a “why did you choose this file?” explanation for each edit.

## J. Providers, performance and advanced workflows
91. [ ] Detect whether a selected model supports tool calling before a task starts.
92. [ ] Add provider-specific context/output-limit detection.
93. [ ] Track approximate tokens per request and keep context bounded.
94. [ ] Send only relevant file chunks, never the entire project by default.
95. [ ] Summarize old tool results while preserving file paths and decisions.
96. [ ] Cache project indexes locally and invalidate them after edits.
97. [ ] Add a model fallback only when the user has configured and approved it.
98. [ ] Add per-task privacy controls for files that may be sent to the provider.
99. [ ] Add a one-click diagnostic bundle with secrets redacted.
100. [ ] Add a full end-to-end acceptance test: select APK → ask for GUI change → discover files → edit → diff → build → verify output → rollback.
