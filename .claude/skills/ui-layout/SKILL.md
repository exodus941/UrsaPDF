---
name: ui-layout
description: Layout, alignment, spacing, colour and accessibility rules for interface work. Load before writing or reviewing UI code, adding a component, making something responsive, or judging a screenshot.
---

# Interface layout

Two reference files sit beside this one. Read them when the topic comes up:

- `toolkit.md`: how the measuring toolkit works, its traps, and how to write a new check.
- `more-rules.md`: older rules, many restating the ones below in other words.

## Measure with the toolkit

`~/.claude/tools/layout-tools.js`. Load it in the page, then:

```js
const src = await (await fetch('/layout-tools.js')).text()
new Function(src + '\nwindow.sweep=sweep;window.probe=probe;window.align=align')()
sweep(root)          // every row in a region
probe('.btn')        // one element in detail
align('.a', '.b')    // two elements against each other
```

Pass an element as the root, never a selector that matches several.

| Work | A milestone sweep after |
|---|---|
| a screen | each section |
| a component | the component with all its states |
| a responsive pass | each breakpoint, at every surface |
| a refactor | each module moved and its callers fixed |
| a guard or a tool | each check, injected and proven to fire |
| a token or scale change | the whole derived set |

Then one deep sweep at the end: every surface, widest and narrowest width,
both modes.

## The stores point at each other

- **`%check%` means every view and every operating system.** It is a completion condition.
- **Read the OS setting out of our own stylesheet, never retype it.** Lift the reduced-motion block from the served file, strip its media wrapper and inject it.
- **The target is the current browser, so a support floor is not a finding.** A feature the current Chrome, Safari and Firefox all ship is available.

## Run the toolkit, never a throwaway probe

- **Read every field**
- **Sweep, do not probe.** A probe aimed at the row you are working on finds problems in that row and nowhere else.
- **Sweep every surface, not the one on screen.** Remembering has never fixed this, so the composing is done ahead of time.
- **Sweep at each milestone, and deeply at the end.**
- **Then one deep sweep at the end**, over every surface, at the widest and the narrowest width you ship, in both modes.
- **Ask when the unit is unclear.** A task that does not fit the table is a question, not a guess.
- **When the tool disagrees with their eye, the tool is incomplete.** Find the hole and add the check.

## Draw the target before you build it

- **Imagine the finished thing as a picture, then build toward it.** New ideas arrive on the way.
- **Draw it, do not describe it.** Prose about a layout is another list.
- **Sample the real palette first, then draw in it.** A grey wireframe answers structural questions only.
- **Build after they react, not before.** Building to see it defeats the point: the picture is meant to be the cheap step.
- **It is an extra gate, not a replacement.** Everything already agreed still applies: the sweep, the guards, the measurements.
- **A draft meets the shipped bar before they see it.** One padding value on the 4px grid on every side, no box larger than its glyph, every inset measured and symmetric. A sloppy draft is never acceptable.
- **The two instruments answer different questions.** The sweep measures a thing that exists; it has no opinion about whether the right thing exists.
- **So: draw for a new surface, a new component or a restructure.**

## Name the pattern before you measure it

- **Read the screen the way a person does: as patterns, contexts and connections.** A reader does not see a mechanism.
- **A control that fits beside the title stays beside the title.** Only when it does not fit does it become a burger, and the burger goes on that same row.
  - **The burger is a wordless square the size of its neighbour button; the section name sits inside the menu, shown by list direction.**
- **A long title wraps or truncates. The mark never moves.** Floor the title at `min-width: 0` and let it wrap; the mark takes `flex: 0 0 auto`.
- **Ask what the pattern implies that nobody wrote down.** A menu opens under the bar that carries it, so the fold must be a sibling of the title row.
- **A measurement of the wrong thing passes.**

## Measure, never eyeball

- **Know what your instrument reports.** A `Range` bottom is the text box bottom, not the baseline — use `rect.top + fontBoundingBoxAscent`.
- **Sweep the whole screen, not the property you were thinking about.** A green check on one property says nothing about the rest.

## Reach for the primitive, never a number

- **A setting reaches the screen as an attribute, never as inline styles.**
- **Put the attribute on the scope, not on the item.** Keyed on `[aria-current]` alone the rule weighs the same as `.nav-item`, so order decides it.
- **Only the subject of a selector counts.** A class to the left of the subject styles a descendant, which is its whole job.
- **A class no stylesheet mentions at all is a hook.** A test id or a behaviour marker is not this question's business.
- **An ancestor class is doing its job.** A row selection class styles the cell, so the class on the row is alive when the row holds one.
- **A state cannot match at rest.** A rule on `:hover` is alive and unmatchable now, so drop the state before asking whether it reaches.
- **A dead rule is the same fault pointed the other way.** A rule that matches no element, such as `.seed-lock:hover > span` over an `<svg>` child, styles nothing and its comment describes an affordance nobody can find.
- **Reaching for an inline value is the signal that a primitive exists and you have not looked for it.**
- **Verify the whole, never the part you touched.** Three cases are one failure.
- **"Not present" is a case, and it is the one that breaks.**
- **Ask what a control is before choosing its class.** A filter opens a list, so it is a select.
- **A control does the obvious thing with its own content.** A colour swatch is a colour, so clicking it edits the colour.
- **Two rows line up when they share a basis, not a grid.** Both rows flex, both hold the same count, and every child takes the same `flex` value.
- **Audit a new screen for strays before calling it done, by query.** A stray is anything hand-rolled beside a primitive that already exists.
- **A test written around a defect keeps the defect.**

## A fill must differ from what is behind it

- **When the palette cannot supply the separation, change the treatment rather than the step.** Every surface in a neutral-derived system is a step on the neutral ramp.
- **An override with no comment is a bug waiting to be found.**

## Situations every product has

- **A record page shows one thing.** A long title, a row of label-over-value facts, tabs over the body, and a column of context beside it.
- **An empty state is four states, never one.** First run offers the feature's primary action.
- **The way back must not be a control that can retire.** A filter menu built from the data offers one option per value it finds.
  - **Reset list-dependent view state once, in the render, from a key of the inputs that define the set.**
- **A field that narrows a menu is not a child of that menu.** A listbox takes option and group children, so a search input inside the `<ul>` is markup the role does not allow.
  - **Its query is not the list's filter.**
- **A loading state holds the shape of what is coming.** Not a spinner.
- **A comparison is read across, so it keeps its columns.** It is the one exception to "too many items".
- **Repeating a track list is not sharing one.** Every row of a comparison belongs to one grid, as a subgrid of it.
- **A label column takes its content, never a share.** Given `1.1fr` it holds that share at every width, and the width comes out of the columns being compared.
- **A recommended option is marked by its edge.** A filled column beside two unfilled ones reads as already chosen, and nobody has chosen anything yet.
- **A marked column keeps its unbroken edge and carries the dividers.**
- **A table of cells that paint has no column gap.** A gap belongs to no cell, so nothing can paint across it.
- **Fixing a seam by moving what draws it can move the seam instead.**
- **A cell centred in its row is shorter than the row.**
- **`grid-row: 1 / -1` needs explicit rows.**
- **Content beside context is not content beside navigation.** They are the same two columns and a different relationship, so they are different classes.

## Alignment and baselines

- **Text baselines are the bible.** Two runs of text in one row sit on one line, whatever their size, weight or colour.
- **The baseline is chosen, not derived.** Decide where the line is, then put every run on it.
- **Measure it with font metrics**, never a rectangle.
- **An element's baseline is not always its text's.** A flex container takes its baseline from its first item, so an ornament decides where the row sits.
- **`align-items: center` and `align-self: flex-start|stretch` remove an item from the baseline set.**
- **A fixed-height box that carries a word uses the line-box technique:** `display: inline-block`, a stated `line-height`, `text-align: center`, `align-self: baseline`.
- **Height and line-height are one decision.** `line-height` equals the content box — the stated height minus the borders.
- **A baseline row inside a fixed-height box pins its own label.** The mark needs the row on the label's baseline, and the box needs its content centred.
- **A mostly-text row takes baseline; a row of fixed-height controls takes centre.** Count what the row holds, and the answer changes with width.
- **Two controls at one height but different tops means the row is baseline.** It reads as a sizing bug and it is an alignment one.
- **Every control height and minimum is `max(its own value, --control-floor)`, never a stated or listed value.** Publish the floor as a property, set once on the scope root inside the pointer query.
- **An avatar is not an icon, so it publishes its own gap.** One step up the scale from the icon gap: 12 against 8.
- **A mark with no text lands on the baseline, not beside it.** A legend dot under `align-items: baseline` takes its baseline from its bottom margin edge, so it sinks.
- **Centre a mark on the cap band of the largest text on its line.** Size its box to the band, and measure the centring on the box.
  - **`align-self` is not optional.**
  - **The percentage resolves against the element own height**
  - **`vertical-align: middle` is what this replaces.**
  - **Make the mark's box the band, and let the drawing overhang it.**
  - **A flex row aligns tops, so two fonts on one line need `align-items: baseline`.**
  - **A native checkbox sets its own baseline.**
- **The row declares the baseline too.** `align-self: baseline` aligns to the flex line baseline.
- **Two controls are excluded, and shipping without them is how I found both.** An icon-only control has no label, so it has no baseline.
- **The mark is 14px in every component and at every button size; any rule that resizes a button restates its icon from the token.**
- **An icon beside a label aligns to the label's cap centre**, not the box.
- **A mark beside a heading needs the same correction, and it grows with the type.** `align-items: center` centres on the heading's box, and a heading's box is far taller than its cap band.
- **A multi-line heading has no cap centre to share.** An item beside a block of two lines centres on the block.
- **A control beside a heading centres on its cap band.** Use the largest published step that fits the band, else the heading's line box. Never use the touch floor.
- **A transform costs no layout, which is why it can overlap.** Where the actions wrap onto a line of their own, nothing is there to centre against. The same lift then pulls them over whatever is above.

## Rows, controls and icons

- **Everything in a control row is one height**, including a mark or a brand square.
- **And that height is stated, never inherited from what is inside.** A control sized by its own contents comes out at whatever its padding, border and label add up to.
  - **The symptom arrives disguised as a misalignment.**
  - **The tell that it is arithmetic and not a fault: the tops differ by exactly half the height difference.**
  - **And the check asks the centres, not the parent's declaration.**
- **A button beside a field matches the field's box and its text baseline.**
- **One button size fixes all three: the box, the label and the icon.**
  - **The label still takes its own step.**
  - **A stale rule is worse than no rule.**
- **A component that publishes a mark size should paint it.** No other mark check compares the published value against the painted one, so ask it.
  - **A control wearing another component's class inherits its rules**
  - **An `<input>` is void, so a child selector on it can never match.**
- **Never correct a glyph.**
- **A tick inside a checkbox is not an icon beside a label.**
  - **A readout is not a control.**
- **Size a control to its own box and put the touch reach on an overhang derived from the host; neighbours clear the target.** The floor is a reach, not a size.
- **A checkbox beside a label that wraps belongs to the first line.**
  - **The flex child is the mark's box, never the glyph.**
- **An icon-only control with a mark is square by aspect ratio with no stated width, and a target is as small as its smaller side.**
- **A hidden label still makes it icon-only.**
- **Grow the box, not the glyph.**
- **Ask the pointer, never the width.** `matchMedia('(pointer: coarse)')`.
- **Never type a glyph where an icon belongs**, and a word space is not a gap.
- **Say which side an icon is on.** `:last-child` counts elements, and a text label is not an element — **CSS cannot see a text label.** Use an explicit class and assert it outside CSS.
  - **Leading is the default, so only the trailing case carries a class.**
  - **Two shapes are placed by layout and carry no class.**
- **Never build an underline from a border.**
  - **The check is a source check, because the render cannot test it.**
- **Icons come from Tabler, not Lucide.** Take Tabler's outline drawing, strip its stroke attributes so the stylesheet sets the weight, and keep the non-scaling stroke on every shape.

## Responsive

- **Name a side logically, not physically.** Write `margin-inline-start` and `text-align: end`, never `margin-left` or "right-align".
  - **The exception is a setting's own value.**
  - **The RTL-specific half is separate, and it is opt-in.**
  - **The checks are the expensive half, so measure before promising them.**
- **Squeezing is not responding.** Check that the layout changed, not that it fit.
- **Ask the container, not the window.** A pane needs container queries; a media query reports the browser and ignores the pane.
- **An inline style cannot hold a query, so a row built inline has no threshold.** It cannot be repaired where it lives, and nobody notices the absence.
- **And ask the right container. One level too high is the same mistake.** Whether an action row fits is a question about the card holding it, never about the app.
  - **Name the box the question is about.**
- **A breakpoint moves a row, never one object.** Name sizes in custom properties, and verify at both widths every time.
- **When a wrapped row's second line should read first, reverse the wrap.** A threshold cannot say it, because the break is a sum of the content's own ink. `order` also reorders a row that has not broken.
  - **The cross axis flips with it, and the fault hides on the wrapped layout.**
- **Sweep every declared width, each midpoint, both pointers with real device emulation, and the layout state the user is in.**
  - **"Fit" is not a width.**
- **A verdict names its own coverage.**
- **Derive a threshold by shrinking the real row until it breaks.** Never by adding up its parts.
- **A sum works on a row with two things in it, and not on four.**
- **`min-content` is not the break when the row holds a flexible field.** A text input's min-content is almost nothing, so the row reports a width at which the field has already collapsed.
- **A threshold moves with the row, and the move is a sum of its own.** Adding a control and shortening two labels is three changes to one number.
- **Publish a threshold as a sum plus a reference, never as a constant.** The number is true of one heading and one set of controls.
- **Three transitions were doing no work.**
- **Measure a part at the width where it is used.** A button that grows at a breakpoint has two natural widths, and the sum is only true on one side.
- **A title beside its actions takes content width, never the free space.**
- **`space-between` spreads the slack across every gap.**
- **The auto margin belongs to the control that needs it.**
- **A wrapper is a box, and a block wrapping an inline child is a taller box.**
- **A gap applies between every pair.** Three items mean two gaps.
- **Never let a heading break mid-word.**
- **A page title keeps its words and takes the lines it needs.** It is the one thing on screen that says where you are, so it wraps rather than truncating.
- **Controls beside a multi-line title centre on one line of it, and which line is a setting.**
  - **A one-line heading makes all three identical**
- **`display: contents` dissolves the row.** Use it to promote a child into the column above, and stop the moment something has to stay on that row.
- **A `.row` does not wrap unless something says so.**
- **A row collapses by rule, never by `flex-wrap`.** Actions beside a heading move below it, keeping the heading's gap floor.
  - **The fault is a break with `nowrap` in force.**
  - **Ask the direction first. A column stacks by design.**
- **An action row that breaks, breaks into pairs.**
  - **The pair should be the container.**
  - **One gap in both axes, because it is one group of buttons.**
  - **Dissolve the pairs when the row fits.**
- **An auto margin does not create space. It takes what the line has.** Wrapping the buttons in a shrink-wrapped box left no free space on that line.
- **Navigation folds behind one control** and never becomes a horizontal strip.
- **Orphaned elements go full width.**
- **Sideways scrolling is a last resort, never a tool.** A run of items that does not fit has too many items.
- **Take a different step, never invent a value** — and **publish a scale, or that rule is unusable.** One value is not a scale.

## Ship no fractional pixel

- **A scale is only useful if a person can hold it in their head.** 12, 16, 24, 32 is a scale somebody repeats from memory a week later. 12.8, 15.1, 22.6 is a lookup table.
- **Spacing steps are multiples of 4; a half-step of 2 lives in a token for distances inside one object, marked in a comment.**
  - **A ratio inherits and a length does not**
  - **A glyph's advance is not a measurement anybody chose.**
  - **A border adds to the box and an outline does not.**
- **Type takes multiples of 4 from 24px up and multiples of 2 below.**
- **Snap at the last step, on the derived pixel.**
- **Two things are exempt, and only two.** The middle term of a `clamp()` is the slope of a line joining two grid endpoints.
  - **The count is the rule, and it is measured.**
  - **Fluid type is off by default, so the first exemption had no candidate.**
- **An icon's stroke is a weight, not a length.** 1.75 is a legitimate answer, chosen against the type beside it.
- **Declare non-scaling-stroke on the shapes, not the svg, and read the child computed style.** An SVG scales its stroke with its viewBox, so `stroke-width` alone paints a different weight at every size.
  - **A per-size token is the wrong fix.**
  - **The chrome is a separate set and drifts on its own.**
- **Check the ink, not only the box.** Position and size checks have no opinion about how heavy a glyph paints, so measure the painted weight.
  - **Do not check the span.**
- **Track and thumb must share parity.**
  - **A variant that moves the track should move the offset.**
- **Keep a `var()` fallback equal to what the token ships.** A fallback is what paints when the token is missing, so a drifted one states a second design nobody chose.
  - **Two contexts, two rules.**

## Measure a settled layout, never a frame

- **A page that runs no frames cannot settle at all.** That rule lives under "A guard's blind spot is the shape it matches".
- **A fixed pause before measuring is a guess**
- **Ask the browser instead of counting milliseconds.** `getAnimations()` names every running animation and transition, and each `finished` promise settles when the element comes to rest.
- **A check that presses something settles before it reads.** `getComputedStyle` during a transition returns the interpolated value, so a frame in it still equals the start and a working toggle reads as dead, intermittently.
- **Re-find the control by selector before every press.** Never hold it across one.
- **`document.body` is not always the painted subject.** An app hosting a preview themes the preview scope, not its own body.
- **A page with no control is a note, not a fault.** A build may put its one control in a shared header, and a small correct build may ship none.
- **A fold animates a grid track `1fr` to `0fr` inside an `overflow: clip` clipper with no padding and `min-height: 0`, toggled by one attribute.**
- **A message that folds away keeps its words until the far end.** Clearing the text on the press empties the box before anything can travel.
- **The fold's cost is the paint of what it moves, never its own layout.** A fold changes the page's height on every frame, so the browser repaints everything below it.
  - **Put the profiler in the app**
- **`align-items: center` says nothing about direction.** It centres on the cross axis, so a centred column carries it exactly as a centred row does.
- **The folding box draws the rule under it with a pseudo-element on the same edge.** It fades its content not itself, and the neighbour holds its line back.
- **A dropdown cannot escape a clipped ancestor**, whichever value clips it.
- **The band's own rule needs a containing block, which a sticky branch cannot promise.**
- **A sticky offset becomes an instruction under `relative`.** `top` does nothing under `static`, so a branch that unsticks a band can hand its own offset back the moment the box is positioned again.
- **Use a pure ease-out; the long duration is only for far travels, chosen by a mark on the root.** `cubic-bezier(0, 0, .2, 1)` leaves at full speed and only lands soft; any x1 above zero holds the start back.
- **Under reduced motion stop only travels and keep fades; a user reporting an OS problem means check reduced motion.**
- **A skipped view transition rejects `ready`.** The browser skips one when a second starts while the first runs, and again when the document is not painting.

## Spacing and structure

- **State both gaps together.** Proximity is a ratio: the gap inside a group against the gap between groups.
  - **The proximity bar is 2:1 for the whole rule; fix a shortfall by raising the outer gap.**
  - **Read why a number is what it is before a rule change moves it, and measure any reason that carries no number.**
- **A group label owns the distance to what it names, and a bare block owns nothing.** An overline above a list is two block siblings, and a block carries no gap of its own.
- **A column heading is also a label, and its sibling is beside it.**
- **A row's `column-gap` is the between-groups number, so it answers to the gap inside a group.**
- **A rule between sections sits inside that gap**, half each side, so a marked and an unmarked boundary occupy the same height.
- **A table cell has a horizontal gutter, and it is the commonest omission.**
- **Keep a table end gutter; the first column starts on the card margin and the selection bar moves outward.** The two outer edges are not one case.
- **Shrink the ornament columns, never grow a content one.**
- **Use fixed table layout with a colgroup, thresholds as sums, a card view that removes the colgroup, and ornament columns reading the control floor.**
  - **The column that shrinks last is the one stating no width.**
- **A heading that wraps costs one line; one that spills covers its neighbour.** Under fixed layout a column can end up narrower than its own heading, and `white-space: nowrap` on `th` then paints over the column beside it.
- **A well is a surface, so it takes the surface radius.** It reads the card's published radius token, never a raw scale step, so a document that squares its cards squares its wells with them.
- **Equal padding on both outer edges.**
- **One mechanism centres a label.** A `.btn` centres by `line-height`; turn it into a flex box and `align-items` centres it too.
- **A gutter between columns is a step of its own**, never the row's default.
- **A shared row class publishes the gap inside a group, so a row of groups needs its own.** Given the same class, a row whose children are groups hands both distances one number.
  - **The ratio is the rule and CSS cannot compute it.**
- **Split a row by what each side holds, not down the middle.**
- **`flex: 1` with `min-width: 0` lets a box shrink under its own label.**
- **A separator goes above each item, never below.**
- **Answer each cut-row edge: a tail under sticky bars, a two-layer eased mask on scroller edges, and a viewport strip at the window foot.**
- **A disclosure opens under the control that opens it, or the control leaves the finger.** A panel above its own button pushes that button down by the panel's whole height when it opens.
  - **Reordering two rows of a sticky band moves four things.**
  - **The sticky offsets**
  - **The band's foot**
  - **The bottom padding of the row that is now last.**
  - **The paint order, which may not move.**
- **A flag that only means something in one layout is cleared on leaving it.** A fold state written by a control that exists at one width band survives a trip through the wide layout.
- **A rule spanning the content width has no corners, so a box whose edge a neighbour draws cannot be rounded.** Move the edge onto the box it belongs to.
  - **Each edge is zero where nothing sits past it.**
- **A scroll timeline fails to absence, which is the bug it was fixing.** A browser without `animation-timeline: scroll()` draws no fade at all, so the repair is invisible where it is needed.
- **Never stack two rules of one weight close together.**
- **Metadata drops below the title; the title keeps its row.** A status chip and a saved-at time only describe the title. They go to a line of their own and free the row for a mark.
- **Sweep the narrowest width you ship, not the one you were thinking of.**
- **A menu control is a button, in the action group, always rightmost.** Not a thing parked against the title — a control beside a heading reads as part of the heading.
- **Make it a sibling of the group, not a member.** A header ladder keeps this one control on the title's row after the others drop below.
- **Rightmost and stays-with-the-title cannot both hold in one wrapping row.** Ordered after the action group the menu is rightmost and wraps with it.
- **A tab strip that does not fit becomes a dropdown.** Replace the bar, do not shrink it and do not bolt a scrollbar on.
- **A tab strip never wraps, and it never scrolls either.** Give it `flex-wrap: nowrap` and let the select handle the width.
- **One container for every section.** A bare block among cards reads as a different kind of thing.
- **A margin on a flex child adds to the container's `gap`.** Write the subtraction.
- **One writer per gap: the container gap or child margins, never both, and no margin to separate items in a wrapping row.** A `row-gap` and a `margin` add, so two writers give a distance nobody chose.
  - **Give cards a default child gap placed first in the file, zeroed for heading groups with `:where()`.**
- **A component type rule can take the mono face off a column of figures.** A rule giving a table cell its own font family is (0,2,0), and so are the mono families on the amount and figure classes.
- **A row delegates its paint to its cells, so a divider check must look one level down.**
  - **A single-child wrapper chain is not a stack, so it is safe to walk.**
- **A published token that nothing reads is invisible to every check.**
- **Two minimums, because a finger and a mouse are different sizes.**
- **A byline belongs to its heading, not under it.**
- **An action stands clear of the text that explains it, by 16px.**
- **In a row of cards, the actions sit on the bottom edge.** `margin-top: auto` on the last child of a flex column takes the free space.
  - **The distance then moves to padding.**
- **A change belongs to the number, not to the tile.** A stat's delta sits closer to its value than the label above it does.
- **A subtraction asks about the parent, so write it on the parent.**
- **A custom property used inside `calc` must exist wherever the calc runs.**
- **Measure the ink, and a mark taller than the line box is a different correction.**
  - **The question is the clearance, not the centring.**
  - **Build the reference, never state it.**
- **A collapsed row still costs its line gap.** An element at `max-height: 0` is on a flex line, and the gap is charged whether anything is in it.
- **Never mix a shorthand and a longhand in one style object.** Order decides, exactly as in a stylesheet.
- **Alignment is stated, never inherited from a group that may vanish.**

## Figures are set in the mono face

- **Figures in a column take the mono face; a standalone figure keeps the body face.** The mono face exists so digits stack over each other.
- **An amount in such a column also takes an end edge.** Money, quantities, percentages, durations — anything you would add up or compare in size.
- **Any other figure in that column takes the face alone, and keeps its normal alignment.** An invoice number, a journal number, an order reference, a version, a hash.
- **State it, because nothing infers it.** A system that names a mono family and never says what it is for gets it used for code samples only.

## Four mechanisms that beat the rule you wrote

- **A guard that asks where an event landed reads it on the capture phase.**
- **Two listeners on one element fire in the order they were added, so the first one cannot read `defaultPrevented`.** It is false there every time.
- **`container-type: inline-size` takes an element out of intrinsic sizing, so a shrink-to-fit box collapses to its padding.** The containment stops the contents contributing to the inline size.
- **A `viewBox` gives an `<svg>` an intrinsic ratio, and it beats a percentage height.**
- **`aspect-ratio` with a minimum size widens the box to keep the ratio.**
- **A flex item whose overflow is not visible has an automatic minimum size of zero.**
- **A reset carries no weight, or it outranks the rules it makes room for.**
- **Two rules sharing one class name is a fault no guard reads.**
- **The `hidden` attribute is an ordinary (0,1,0), so any component class beats it.** The UA rule is `[hidden] { display: none }`, and an author class at equal specificity wins.
- **`overflow: hidden` clips the element's own pseudo-element, so a target overhang cannot sit on a box that truncates.** An absolutely placed `::after` at a negative inset is the way to reach the touch floor without moving any layout.
- **In a column flex container, `flex: 1` means `flex-basis: 0` on the block axis, and it outranks `height`.** The same declaration is correct in a row, where the basis is a width.
- **A standard scrollbar property disables every `::-webkit-scrollbar` rule.** Chrome takes `scrollbar-width` and `scrollbar-color` where they are declared, and then disregards the pseudo-elements outright.
  - **A second scroller takes the same split, not a second block.**
  - **The handle is the track minus two borders**
  - **The thumb takes no transition**

## Colour and accessibility

- **A floor you cannot find is a sign the question is wrong.** When a swept threshold has no good answer anywhere, stop tuning it and change the mechanism.
- **A type role is one decision, so it is one class.** An `h3` carrying `font-size: var(--font-h5-size)` renders h5's size on h3's leading, so the published h5 value never reaches the screen.
- **A check asks only what is unambiguous.** Whether a selection steps up or down is a treatment decision. So the checker asks only that the fill differs from its ground by a step the eye can find.
- **A transparent colour is not black.**
- **A heavier weight buys apparent contrast that no ratio reports.**
- **Check every mode the system ships.**
- **A component's own text on its own fill is a third pair** that no role table measures.
- **A line is never the colour of what it divides.** Below about 1.2:1 a hairline is absent, not subtle.
- **One role, one requirement.** A role serving two uses with different bars cannot satisfy both — split it.
- **A control's outline is chosen against the ground it sits on.** The default border token is calibrated for the card and silently fails one step down.
  - **State the range, not one palette's figure.**
- **Measure a fill against what it replaces and a role on its worst ground, using the heaviest alpha; darken the role, not the tint.**
  - **Prove the sweeps are exhaustive.**
  - **The two sweeps promise different things, so one quiet-on-correct-code assertion cannot serve both.**
- **A selected row is not an accent-subtle background.** `accent-subtle` is the quiet ground behind accent content and measures 1.26:1 on the surface.
- **A stripe is rhythm, a band is a boundary.** Alternate rows want the softest step the ramp holds.
- **Lightening a selected row also fixes the control on it.**
- **A stripe and a row rule do different jobs, so a long table takes both.** The band carries the rhythm across a wide row, so the eye keeps its place; the rule marks where one row ends.
- **Two accessibility rules can conflict.** Pick the failure with a remedy at the point of use, then state the limit in the output.
- **Colour carries one meaning each, and meaning never rests on colour alone.** A sign as well as a hue.
- **"Do these read as one colour" needs hue and lightness.**
- **Name only what you ship**, and **a fallback hides a missing token**.
- **One instrument measures one thing, and its answer is not the whole answer.**

## Specifying for other agents

- **A checkbox has three states.** Indeterminate is the honest answer when some of the rows below a select-all box are chosen and some are not.
- **A checkbox carries a visible label, unless the label is positional.** The label may come from the structure the box sits in.
  - **The select-all box goes at the top of the column it controls**
  - **A count is a readout, not a control.**
  - **A positional label still owes an accessible name.**
- **Say which keys each component answers.** Take them from the ARIA Authoring Practices Guide, so nothing is invented.
  - **Check it, or it is prose.**
- **Paint is not a state.** A tab marked only by a shadow and a weight tells a screen reader nothing. A forced-colors rule keyed on a class it lacks matches nothing.
- **An overlay is not a card in a page, and it has to say so.** It takes `role="dialog"`, or `alertdialog` where the message is the reason to stop.
- **`disabled` and `aria-disabled` are not interchangeable.** `disabled` removes the control from the tab order, so a pager's Previous arrow vanished on page one.
- **An invalid field points at its own message.** `aria-invalid` alone says something is wrong and never what.
- **A spread replaces a prop. It does not add to it.** A helper's `onClick` spread after a button's own handler deletes that handler, and every check still passes.
- **A restructure reaches states the old structure could not.** After moving an element between containers, ask what the container it left looks like empty.
- **A comment left open swallows the rule after it, and every other rule still applies.** Prose left bare between two comment closers makes CSS error recovery discard exactly one declaration block, silently.
