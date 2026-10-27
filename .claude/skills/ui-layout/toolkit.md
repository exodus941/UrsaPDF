# Toolkit internals and measuring traps

Read when writing or changing a check in `layout-tools.js`, or when a
measurement disagrees with the eye.

## The stores point at each other

- **A hidden control is absent, not working.** Ask `checkVisibility` before pressing.

## Run the toolkit, never a throwaway probe

- **Name what answers to what.**
- **A tag list finds the cases somebody already thought of, and approves the rest.** Ask the property, never a list of class names or a substring.
- **A row carries its label's type, or the cap band is computed from the wrong size.** The band is stated as `0.75em`, and `em` resolves against the element carrying the transform.
- **A text-less wrapper takes its baseline from its bottom margin edge.** Where an editor wraps each instance, the wrapper is the flex item, so put the rule on the wrapper.
- **`getComputedStyle().height` never returns `auto` for a rendered element**, so never write a guard as "only where a height is stated".
- **The repair for a false positive is the fault, not an exemption.** Ask the fault itself rather than exempting the components it misfires on.
- **A control is one object, not a group of items.** A control's children are ornament — a mark, a label, a chevron — so the control is a leaf whatever its display.
- **A specimen sheet is exempt.** A row whose job is to show three sizes cannot be faulted for showing three sizes.
- **A rule wider than its problem is a bigger bug than the problem.**
- **A wrong line number is worse than none.** Stripping comments by deleting them takes their newlines too, and every number below shifts.
- **Never quiet a check you have not retested.**
- **A selector that matches more than one element is not a root.**
- **A check that presses something runs last.** A restore press puts the state back but cannot unmount what the change mounted, so order is the fix.
- **Resolve the cascade, including the absent case, and read back the computed value.** Collecting every rule that declares a property answers "does anything say this", never "is this in force".
- **Never trade one miss for another when widening a check.**
- **Give each check its own candidate filter, and narrow ghost findings on declared state.** The row checks need a list with the invisible thrown out.
- **A narrowing is what goes silent, so prove it on every page.** Run one fixture per clause whenever the toolkit loads, and let a failure withdraw the sweep's own `clean`.
- **Measure the ink, never the wrapper.** A burger is three spans inside a summary inside a `details`.
- **Content clipped with no way to reach it is the most expensive fault, and nothing was checking it.**
- **A cross-fade keeps two trees mounted, so the first match is the outgoing one.** A dissolve needs both surfaces on screen, and the outgoing layer mounts first.
- **Round once, at the end.** Subtract in floats and round the answer.
- **Put a threshold on the move, not on the difference.**
- **Adjust the measurement, never the number.** When a metric answers a different question than the one you are asking, stop correcting its output and measure the right thing.

## Responsive

- **A rule that compares two runs needs a snapshot, not a better check.** A render check sees one surface, at one width, with one pointer.
  - **Keyed by name, not by index.**
  - **Ask the surface, not one container.**
- **Ask the threshold the stylesheet declares, not the token the document publishes.** Published breakpoint tokens serve somebody else's build, so the preview not reading one is not a fault.

## A guard's blind spot is the shape it matches

- **`display: contents` is not a child. Its children are.** A dissolved wrapper generates no box, so a check that walks `el.children` measures it as zero width, skips it, and loses everything inside it.
- **"One row" means one level, not any depth.** `:scope > * svg` is a descendant selector and matches marks at any depth.
- **Reading a rectangle inside `content-visibility: auto` renders that subtree, so one read per row is one layout per row.**
  - **Measure one thing once, on a probe.**
  - **And ask an attribute where the question is a declaration.**
- **Measure only a fresh page and a freshly emitted served file, and make no source or config edit while a run is in flight.** A dev page open through many hot updates drifts from the source.
- **A gap shorthand sets both axes, so a column declares a gap that paints nothing.** A check reading computed `columnGap` on a column flex box reads an axis its children never sit along.
- **An explanation is prose, and the markup says so.** A word count cannot tell a sentence that explains an action from a readout in a pager bar.
- **Slack is not a gap.** A row that pushes a group right with `margin-left: auto` holds one distance nobody chose.
- **`getComputedStyle` cannot see an auto margin.**
- **Resolve a selector list to a set once, never per comparison.**
- **A check that makes the tool unusable costs more than the false positive it removes.** Time the run before and after.
- **A scope defined by `closest()` flips when the markup moves.** A selector list picks whichever matches first, and that is a property of the code being audited.
  - **A body may ask whether an ancestor exists, and may not keep the answer.**
- **A driver re-finds controls by selector, polls until the requested state lands, and reloads rather than running beside an old runner.**
- **A summary that truncates says so.** Print `+ N more not listed`, and return a grouped tally beside the window, so the reader learns what is missing as well as how many.
- **An instrument left on the page becomes one of the things it measures.** Resolving a token means painting it on a probe and reading the engine's answer back.
- **Never poll with `requestAnimationFrame` in a tool.** A background tab runs no animation frames at all, so the loop never resolves and the run hangs.
- **An infinite animation never finishes.** `getAnimations()` returns it, and its `finished` promise never settles, so a bare `Promise.all` waits for ever.
- **Before trusting a settled reading, prove frames run by advancing a script animation, and keep a long run inside one call.**
- **Under viewport emulation, `innerWidth` reports the host pane and not the page.**
  - **Assert the width you asked for before trusting the run**

## Specifying for other agents

- **An audit of the declaration cannot see the rendering.** Pair every declaration check with one that walks what the engine actually rendered.
- **A partner control is a sibling, never anything `querySelector` can reach.** `parentElement.querySelector` searches the whole subtree and can find an unrelated input elsewhere on the screen.

## Run the tool. Do not rewrite it in the probe.

- **Read the whole result.** `sweep` returns ghosts, covered, tops, heights, baselines, icons, gaps, edges, overflow, scrollers and targets.
- **Ask layout and paint together, never one at a time.**
- **Measure, do not look.**
- **Metrical is not optical.** Geometry agreeing is not the same as looking right.
- **A capped child does not centre in a stretch container.** `align-items: stretch` only centres things with no width of their own.
- **Settle before you measure.** How to settle is stated once in `SKILL.md`, under the settled-layout section.

## When the tool disagrees with their eye, fix the tool

- **A check that returns null on a shape it does not recognise reads as a pass.**
- **"No answer" is not "no".**
- **A verdict that flips on floating-point noise is worse than none.**
- **Measure against the reference the author chose.** A leading icon is supposed to sit left of the box centre.
- **Invisible on purpose is a different fault from invisible by accident.** Opacity 0 *with a transition on opacity* is an idle state that fades in.
- **Scrolled out is not covered, clipped is not covered, and under a scrim is not covered.** Nor is `pointer-events: none`, which means unclickable, not invisible — `elementFromPoint` can never name such an element, so it looks buried forever.
- **A wrapped row is several rows.** Band items by whether their ink overlaps vertically, not by how close their baselines are.
- **Only a single line can be judged against a baseline.**
- **A rule that fires everywhere tells you nothing about anywhere.**
- **A report you cannot trace back to an element is not a report.** `el.className` is an `SVGAnimatedString` on an SVG, so six findings printed as `[object SVGAnimatedString]`.
- **Measure the visible box, never the laid-out one.**
