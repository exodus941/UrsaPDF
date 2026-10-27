# More layout rules

Older rules from the first version of this skill. Many restate rules in
`SKILL.md` in other words.

## Rule zero: never eyeball what you can measure

- **You cannot see the screen.** A screenshot is a grid of pixels you are guessing about.
- **And measure the fix, not just the fault.**

## The four I skip, and the one call that stops me

- **And never redirect a guard to /dev/null.** `guard && echo ok` prints nothing when it fails, which reads exactly like success.

## What alignment actually means

- **Line-height must match the content box, not the border box.** This is the one that hides best, and I wrote the wrong explanation for it twice.
- **A fractional line box does not centre either.**
- **Round it, do not shrink it.**

## Choosing a reference

- **Text beside text** → baseline.
- **Icon beside text** → cap centre of the text.
- **Icon alone in a control** → box centre.
- **Control beside control** → equal heights first.
- **Item beside a multi-line block** → centre on the block, unless the item belongs to the first line specifically, like a checkbox or a bullet.
- **Stacked items** → left edges, not centres.

## Rhythm and grouping

- **Unequal gaps in one row look accidental.** Three items with 8, 8 and 14 between them reads as a mistake even when nothing is misaligned.
- **Proximity is grouping.**

## Baselines are the bible

- **Text sits on a baseline, and baselines must line up.** This is not one rule among many.
- **One line means one baseline.** An item beside a single line of text aligns to that baseline.
- **Three traps that silently break a baseline:** 1.
- **A fixed-height control cannot both centre its label and donate that label's baseline** using flex.

## Buttons

- **An icon-only button is still a button.** Give it the same height as its siblings and a square footprint, not its own component. The square rule is stated once in `SKILL.md`.

## Icons

- **An icon must look like the thing it names.** If someone has to be told the glyph is a wrench, redraw it.
- **An icon inside a control is not decoration.** It gets the size token for that control's size step, and the gap comes from the icon-gap token, not from a number you liked.
- **A colour swatch, a lock, a dot: draw it in whichever of black or white actually reads against what it sits on.** Compute it per colour rather than picking one and hoping.

## Declared is not the same as perceptible

- **Too quick** — declared, running, and over before the eye resolves it.
- **Prove a hover runs, do not assume.** A synthetic hover does not fire `:hover`, and a pane that is not compositing reports the end state instantly.

## An interface uses its own motion tokens

- **Verify a transition with `getAnimations()`, not by sampling.** A transition only advances while the page composites, so a pane that is not being painted reports the end value the moment you ask.

## A closed `details` renders nothing but its summary

- **Check paint, not just layout.**

## A host's click handler can swallow a control's behaviour

- **A host's click handler can swallow a control's behaviour** An overlay that makes elements inspectable usually calls `preventDefault` on

## One breakpoint per question, measured from the thing it governs

- **Measure the number, do not pick it.**
- **Collapse in stages, cheapest thing first.** Decoration before content, content before action.
- **Test the bands, not the corners.**

## Orphaned elements go full width

- **The condition: if an element ends up alone on its own line, it takes the whole line.**
- **A mark in a control row is part of that row.** It takes the row's height and stays 1:1.
- **`width` alone does not hold a size in a flex row.**

## Re-sweep the row you just changed

- **Every restructure breaks heights. Every time.**

## Rows of controls

- **A global `label` margin breaks centring.** A stylesheet that gives `label` a `margin-bottom` for stacked forms will silently misalign it inside a row, because flexbox centres the *margin* box.
- **`min-height`, not `height`, on a row.**
- **Give a row room to breathe.** Cramped is a defect, not a density choice.

## Readouts and reachability

- **A readout never renders blank.** When there is no numeric value, print the name of the state — "Fit", not an empty slot.
- **Hiding a control needs an alternative route.** Removing a button at a narrow width and giving it no home in a menu does not simplify the screen, it deletes the feature.
- **If it looks like a control, it obeys the control rules.** A bell that renders as a button is a button: same height, same baseline, same target size as its neighbours.

## Scrolling to an anchor

- **Scrolling to an anchor**

## Responsive

- **Tables scroll sideways.**
- **State the arrangement, do not just wrap.** `flex-wrap` lets items flow and strands the last control beside the second-last one, with a ragged edge and no reading order.
- **`minmax(0, 1fr)`, not `1fr`.**
- **Container queries when the thing is a pane.** A media query inside a preview pane reports the browser width and ignores the pane, so a width control does nothing.
- **Controls grow for a finger.**

## A control must be able to display its own value

- **A control must be able to display its own value**. If a select is too narrow to show what it is set to, it is broken.

## An element's baseline is not always its text's baseline

- **A flex container takes its baseline from its first flex item.**
- **An inline-block has a baseline of its own**, from its own line box, and no child can move it.
- **An inline-block with no in-flow content uses its bottom margin edge** as its baseline.
- **An `svg` has no baseline**, so a baseline-aligned row sits its bottom edge on the line.

## Height and line-height are one decision, never two

- **And when you change a height, you change the line-height in the same edit.**

## Symmetric padding does not optically centre text

- **A taller sibling on the same baseline grows the box downward only.**
- **Judge padding by the result, never by the symmetry.** Unequal padding with centred text is a correction that worked.

## Equal boxes and equal baselines need one font-size

- **Equal boxes and equal baselines need one font-size** Two controls of the same height, each centring its own label, put their

## Grow the box, not the glyph

- **A control clears the floor when its targets do, not when its container does.**

## A text label is not an element, and CSS cannot see it

- **There is no CSS selector for "is there text next to this".** Stop looking for one.

## Verify a sizing rule against the thing it sizes

- **Verify a sizing rule against the thing it sizes**

## An inline style beats your stylesheet

- **An inline style beats your stylesheet** A rule that loses to an inline value is not a fix, it is a rule you have not

## Design practice

- **A heading owns its line.** A two-word heading forced onto two lines by a button beside it is a layout failure, not a long heading.
- **Read before act.**
- **Primary action first when stacked.** A footer reading Cancel, Save draft, Save changes is correct in a row, because the eye finishes on the right.
- **Do not shrink the thing the page is asking for.** Two hero calls to action sharing a cramped line is worse than one full-width primary with its alternative underneath.
- **A two-word heading must not break in half.** "Account settings" stacked as "Account" over "settings" looks broken.
- **A header's links fold into the menu with it.**
- **Navigation folds, and the content does not move down.** Collapsing a sidebar into a stack above the page title is not a small-screen layout.
- **Cramped is a defect.** Compare the padding against the rows above and below before you decide something is dense on purpose.
- **Every control shows its own value and stays reachable.** A select too narrow to display what it is set to is broken.
- **Respect the system's own values.** When the interface renders somebody else's design system, do not quietly override their token to satisfy a rule.
- **A status readout is not a control.** If it renders as a bordered pill with a label, people will click it.
- **A tab is somewhere you go.** Something you glance at and dismiss is a chip or a bar, not a tab.
- **Put a control beside the thing it describes.** A contrast readout belongs next to the palette it grades, not on a bar where you set unrelated values.
- **An affordance nobody can see is not one.** A row that reveals its actions on hover has no affordance on a touch screen.
- **Two marks that mean opposite things must not be the same mark.**

## Before you say it is done

- **`sweep()` came back clean**, on the chrome as well as the content, after the last edit rather than before it.
- **You checked every screen, not one.** Fixing the first surface and shipping is the most common way this work goes wrong.
- **You judged it, not only measured it.**

## A row that stops fitting collapses by a rule, never by flex-wrap

- **Actions beside a heading move below it.** They never wrap inside the heading's row.
- **There is no cap on how many actions may sit beside a heading.** As many as fit at their natural widths belong on that line.
- **Then break by importance — four rules, any number of buttons:** 1.
- **Never leave an icon-only button alone on a line at its natural width.** Pair it with the button above it, or give it a label and let it fill.
- **Navigation is not an action row.** It has two states, full or a single menu button, and never a horizontal strip in between.

## When it is not clear, ask — do not pick and hope

- **Do everything that does not depend on the answer first.**

## A breakpoint moves a row, never one object in it

- **Name the size once, in a custom property.** A media query can reach a custom property.
- **Verify at both widths, every time.** A responsive rule checked at one width is half tested. The untested half is where the object you forgot is sitting.
- **The toolkit does this now.**
- **It bands by what looks like a row, not by DOM parent.** Grouping by shared parent misses a mark and buttons that live in two different flex containers.

## The gap beside a heading is a floor, and it is what decides the collapse

- **That floor is what decides when the row breaks.** The question is never "do the actions still fit".

## Measure a baseline with font metrics, never with a rectangle

- **Check the row, not one element.** Collect every run's baseline, count the distinct values, and report the spread.
- **The rules were never the bottleneck.** That payload already carried twenty baseline rules, well written.
- **Writing the snippet into generated prose:** Use concatenation, not a template literal.

## Publish a scale, or "take a different step" is unusable

- **A rule that says "use the next step" needs a next step to exist.** One value is not a scale.

## One role, one requirement — and ask where it is used before repainting

- **The paint was never wrong. The role was doing two jobs with different requirements.** 1.4.3 exempts text inside a disabled control and does not exempt a placeholder.
- **What settled it was asking where the role is actually used**, rather than reasoning about the ramp.
- **Before changing a value to satisfy a check, grep for where the token is used.** A role that fails everywhere and is used nowhere is a naming problem.
- **The curated pair was hiding it.**
- **An exempt pair still prints its number.** Grade it "Exempt (1.4.3)", not "Fail" and not nothing.

## A line is never the colour of what it divides

- **No WCAG number applies here, and the bar is lower than any of them.** A line within a hair of its background is absent, not subtle.

## Two defensible readings means state the choice

- **Where a system's prose and its examples disagree, an agent decides per label, and the page comes out half one convention.**
- **And cover the case where the setting cannot be honoured.** A light-only system asked for a theme toggle must be told not to build one, explicitly.

## A mostly-text row takes baseline. The boxes in it opt out.

- **Nothing "opts out". I wrote that twice and it was wrong both times.** First I wrote that the boxes opt out of the baseline.
- **And every run of text on the row belongs on it. All of them.** A logotype inside a square is still text on the line.
- **A box inside a baseline-aligned text group is positioned by that group, not by its own height.**
- **Do not read this as "always baseline".** A row of nothing but fixed-height controls still centres.
- **The real lesson, in their words:**

## A restructure creates states the old structure could not reach

- **So after moving an element between containers, ask what the container it left now looks like when its remaining children are all hidden.**

## Count what the row holds — and the answer changes with width

- **A control with no visible text has no baseline to offer.**

## An icon-only control takes the touch floor, and that is also how it fits

- **Reach for the system's own floor before reaching for a smaller gap.**

## A component's own text on its own fill is a third pair

- **`transparent` parses as a colour.**
- **One decimal place lies at a threshold.**

## Drive the interface the way a person does, and type as well as drag

- **Drive the interface the way a person does, and type as well as drag** A slider exercises one half of a numeric control.

## Auditing your own notes: grep is a bad check on wrapped prose

- **Auditing your own notes: grep is a bad check on wrapped prose**

## State the gap inside a group and the gap between groups together

- **Two sources feeding one gap is the trap underneath this.** A margin on a child of a flex or grid container adds to that container's `gap`.

## Three ways a new arrangement check cried wolf

- **An icon-only button is not empty.** Its mark is a background or a pseudo-element, so it has no text and no element children. How to detect it is stated once in `SKILL.md`.
- **A spacer is only a fault when it costs something.** A `flex: 1` div pushing one button to the far end is a normal idiom and harms nothing while the row fits.
- **Band by ink overlap, never by distinct tops.**
- **And measure a fill against the content box.**

## A width never tells you what is doing the pointing

- **Ask the pointer, and only the pointer.** A width tells you how much room there is.
- **And the cost of a false positive is not just noise.** It spends the user's attention on a decision that does not exist, and it teaches them that the findings need checking before they mean anything.

## One bare block among cards reads as a different kind of thing

- **Consistency of container is what lets a person stop looking at the frame and start reading the contents.**

## A separator goes above each item, never below

- **Write the drift assertion from the emitted text, never from your intent.**

## Never type a glyph where an icon belongs

- **A text glyph is not an icon.** Not a plus, not a cross, not an arrow, not a chevron.
- **And a word space is not a gap.** It is roughly a quarter of the font size, it belongs to the text, and no spacing token controls it.
- **Search for the class, not the instance.**

## A lens is not an edit

- **The membership test is not whether it feels like a preference.**
- **Keep it out of the past, not out of the state.**
- **Then check the other direction.** A real edit must still save and still say so.

## One writer per property, and the second starter cancels the first

- **Whoever starts second takes sole ownership and cancels the first.**
- **A tolerance-based "did someone else move it" check is the wrong instrument here.**
- **Check the reverse order too, then say why it is safe.** For example, a wheel cannot interrupt a chevron when the listener is on the scroller. The chevrons are its siblings, so a wheel over one never reaches the handler.
- **And the general lesson: adding a second way to do a thing is a change to the first way.**
