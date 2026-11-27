# Getting started with PhaseCraft

## Requirements
- **Minecraft Java Edition 26.3** (stable, not the 26.4 snapshot)
- Fabric Loader for 26.3 and a **Fabric API JAR for 26.3**
- Optional: **Sodium** works with PhaseCraft (tested with Sodium 0.9.3 for
  26.3, OpenGL): the overlay, editing mode, phased light and phase piston
  movement look the same as without it.
- Java Development Kit 25 (for local builds)
- Git (Gradle 9.8.2 is fetched by the committed wrapper, `./gradlew`)
- Visual Studio Code with Java and Gradle extensions, or IntelliJ IDEA

The project targets 26.3; the older 1.21.1 PhaseCraft build **will not load**
under Minecraft 26.3. Download a fresh artifact from the latest successful
GitHub Actions run and put the mod JAR (not the artifact ZIP) into the
instance's `mods` folder together with the Fabric API for 26.3.

The Gradle wrapper is committed and pins Gradle 9.8.2; use `./gradlew`
(or `gradlew.bat` on Windows) rather than a system Gradle. Only `JAVA_HOME`
pointing at a JDK 25 is needed.

## Build and run

```sh
./gradlew build
./gradlew runClient
```

The hidden layer is a real dimension at the same coordinates
(`phasecraft:hidden_overworld` for the Overworld). In a Creative world with
cheats enabled:

```text
/setblock 10 64 10 minecraft:stone
/phasecraft set 10 63 10 minecraft:stone
/phasecraft set 10 64 10 minecraft:redstone_wire
/phasecraft get 10 64 10
/phasecraft layer hidden
/phasecraft layer visible
```

The hidden layer follows vanilla rules, so the hidden wire needs a hidden
block under it (the second line); without one it breaks, as it would
anywhere. The block argument uses the same syntax as `/setblock`, with tab
completion: `redstone_wire`, `minecraft:redstone_wire` and
`minecraft:repeater[facing=east,delay=3]` all work.

`get` reports visible stone and hidden
`minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]`.
`/phasecraft layer hidden` takes you to the same spot in the hidden layer
(Creative or Spectator only: it has no ground; switch to Survival there and
you are put back in the visible world), where you can see and build
hidden circuits with normal blocks and commands; `layer visible` brings you
back. Hidden redstone runs by vanilla rules.

To connect the layers, put a phase port in the hidden layer. It works both
ways at its spot, and nothing shows in the visible world: visible power at its
spot (a lever there or next to it) comes out of it into the hidden layer, and
hidden power reaching it comes out in the visible world at its spot, as if an
invisible redstone block stood there.

```text
/setblock 12 64 10 minecraft:lever[face=floor,powered=false]
/phasecraft set 12 63 10 minecraft:stone
/phasecraft set 12 64 10 phasecraft:phase_port
/phasecraft set 13 64 10 minecraft:redstone_lamp
```

Flip the (visible) lever and the hidden lamp next to the hidden port lights.

### Seeing the hidden layer: the overlay

In Creative, Spectator, or Survival (see "Survival play" below), hidden blocks near you (about 70 blocks around)
are drawn as see-through ghosts over the visible world: clearly where
nothing visible is in front of them, fainter through visible blocks, so
machinery inside a wall or under the floor still shows.

| Key (Controls, "PhaseCraft") | Does |
| --- | --- |
| **H** | Show or hide the overlay |
| **]** / **[** | More opaque / more see-through (15% to 100%, in steps of 10) |

The setting is kept in `config/phasecraft-client.properties`. Adventure
players, and Survival players when the game rule is off, are sent nothing,
so hidden doors stay hidden from them. The overlay needs PhaseCraft on the
client as well as on the server.

### Building in the hidden layer: editing mode

Press **J** (Creative, or Survival, see below) and "Editing: hidden layer" shows in the top left.
Your clicks now go to the hidden layer; the visible world is not touched.
Aim at a hidden block and the line under it names the block and its state, e.g.
"Shifter Piston · delay 3 · facing up" (on/off properties show only when on).
What a click acts on is outlined in purple:

- **Aiming at a hidden block** (even one inside a wall, as the overlay shows
  it): right click uses it (flip a hidden lever, set a hidden repeater's
  delay) or places your block next to it; left click breaks it; middle click
  picks it into your hand (Ctrl + middle click in Creative also copies its
  contents, as vanilla does: a hidden chest with its items).
- **Aiming at a visible block with nothing hidden there:** right click puts
  your block in the hidden layer *at that same spot*, overlapping the
  visible block. That is how you hide a lamp inside a wall or a port under a
  lever. Left click does nothing.

Placing follows vanilla rules in the hidden layer, so hidden wire, repeaters
and floor levers need a hidden block under them: right click the visible
floor with stone first (it goes into the floor), then place on top of that.
Press J again to go back to normal building. The overlay is shown while you
edit, even if H turned it off. Buckets work too: a full bucket pours hidden
water or lava (at the spot you aim at, or next to the hidden block you
aim at), an empty one scoops hidden water or lava up. Fish buckets are
refused.

![Editing mode: a hidden lamp placed at the spot of a visible stone block, outlined in purple](images/editing.png)

### Survival play

PhaseCraft works in Survival. The game rule `phasecraft:survival_access`
(on by default) lets Survival players use the overlay and editing mode, by
Survival rules (each PhaseCraft item's tooltip says what it does):

- placing a hidden block uses up the item;
- breaking one takes its normal mining time with your tool (cracks show on
  it); it drops in the visible world, if your tool is right for it, and the
  tool wears;
- middle click selects a matching item you carry; it never makes one;
- going into the hidden layer itself stays Creative/Spectator only.

`/gamerule phasecraft:survival_access false` turns it off: on a public
server, Survival editing lets players build inside other people's walls
(claim mods only watch the visible world). Adventure players never can.

Recipes (a starting point for balancing):

| Item | Recipe |
| --- | --- |
| Phase Block x4 | an amethyst shard in the middle, redstone dust on the four sides, cobblestone in the corners |
| Phase Port | Phase Block + redstone torch |
| Phase Piston | piston + Phase Block |
| Sticky Phase Piston | Phase Piston + slime ball, or sticky piston + Phase Block |
| Phase Shifter (2) | Phase Block + ender pearl + piston |
| Shifter Piston | Phase Shifter + piston |
| Area Shifter (2) | Shifter Piston + diamond block |

### Secret switches: the phase block

A hidden block standing on a **phase block** (Redstone tab), or hanging on
its side, is *phased*: it reacts to the visible world at its own spot.

- **Buttons and levers:** right-click the visible block at their spot. A
  hidden button inside a brick wall, hanging on a hidden phase block behind
  it, is a secret brick: anyone can press it, in Survival too, and nothing
  shows where it is. Sneak with something in hand to place against that
  brick as usual.
- **Pressure plates:** players, mobs and items standing on their spot in the
  visible world press them. Put the hidden phase block in the floor (at the
  same spot as a visible floor block) and the hidden plate on it.
- **Light sources** (glowstone, lamps, torches, lanterns...): they light the
  visible world at their spot, by vanilla's light rules, for every player.
  A hidden lamp inside a wall, on a phase block, lights the room in front of
  it; hidden redstone switches it. A wall torch counts as hanging on the
  block behind it, a hanging lantern on the block above.
- **Observers:** a phased observer watches the visible block in front of
  it: place, break or change a block there and it pulses.
- **Comparators:** a phased comparator reads the visible block behind it: a
  chest's fill level, a cake, a composter, a full item frame behind a solid
  block, as vanilla does. It never reads visible redstone power (that is
  what phase ports are for).
- **Tripwire:** hidden tripwire hooks hanging on phase blocks, with hidden
  string between them, are tripped by players, mobs and items walking
  through the string's spots in the visible world. One hook on a phase
  block is enough.
- **Hoppers:** a phased hopper pulls from the visible world above it: a
  visible chest standing on it, or items lying on top of its spot. Hide it
  in the floor under a chest and whatever goes into the chest disappears
  into your hidden machinery.
- **Droppers and dispensers:** a phased dropper or dispenser puts out into
  the visible world in front of it: into a visible chest there, or out into
  the world (items, arrows, water, fire charges... whatever a dispenser does).
  With phased hoppers in and phased droppers out, an item sorter can take
  from visible chests and fill visible chests with none of it showing.
- They make no sound in the visible world, and neither do phase pistons:
  hidden machinery is silent there.

Not supported yet (phased, they act as ordinary hidden blocks): other
blocks, and arrows on wooden buttons. Pistons cannot move a phase block.
Examples station 13 has a secret brick and a secret plate, each lighting a
visible lamp through a hidden phase port next to its phase block; station 14 is a dark hut with a secret
brick that switches on a lamp hidden in its wall.

Station 19 has three watchers, each lighting a visible lamp: put anything in
the chest (a phased comparator reads it), walk through the narrow doorway (a
phased tripwire), or place or break a block on the chiseled brick (a phased
observer toggles a hidden copper bulb).

Station 20 is item mail: put items in the chest, then click the marked tile
in the ground a few blocks east; one item pops up out of the ground each
time (a phased hopper under the chest, hidden hoppers, a phased dropper in
the ground and a phased button under the tile).

Station 15 is a flying machine with a hidden engine: click the top of the
post and a gold block slides east by itself, pushed each cycle by a phase
piston riding on a vanilla flying-machine engine in the hidden layer
(overlay on, H, to watch the engine). Hidden obsidian stops it after eight
blocks; `python3 tools/examples.py` puts it back.

Station 16 is a secret room behind a wall of bookshelves. One of the
bookshelves facing the path is a switch (a phased button hides behind it), and
so is the one behind it on the room side: click it and two bookshelves slide
away into the wall; click either switch again and they close.

![A plain wall of bookshelves](images/secret-room-wall.png)

Station 17 is a trapdoor floor: walk down the raised corridor and, a few
tiles in, the floor ahead drops away into a pit (a hidden pressure plate on a
phase block is the trigger). A tunnel leads out of the pit. The floor closes
again a moment after you leave the trigger tile.

Station 18 shows the phase shifter: a plain stone-brick wall where one brick
(right of the middle) hides a lever. Click it and four bricks vanish, leaving
a doorway; click again and they come back. Behind each of those bricks a
hidden phase shifter holds the brick while it is powered. The Phase Shifter
is in the Redstone tab; crafting it (Phase Block + ender pearl + piston) makes
two. Like a piston it passes no power on: a repeater into one shifter of a
wall takes just that block. It takes what a piston could push, and chests, furnaces, barrels and
other blocks with contents too: they go with everything in them and come
back exactly as they were. If you build something in the gap meanwhile,
the returning block breaks it (it drops as an item). Someone standing in the
gap is lifted onto the returning block, as a piston pushing up would; only an
unbreakable block like bedrock, or no room above them, makes it wait.

The **Shifter Piston** (Phase Shifter + piston; called the line shifter in the
code and the developer docs) does the same for a whole row
or column, with a piston's rules. It faces a way, like a piston (it faces
you when you place it), and works on the visible blocks in front of it: when
powered it takes them one by one from the far end, and when the power goes
it puts them back one by one from its own end, so the line grows back out of
it. It takes the line a piston there could push: up to the first gap, at
most 12 blocks, and nothing at all if there are 13 or something immovable
like obsidian is in the way. Click it to set its speed, as on a repeater:
one block every 1 to 4 redstone ticks. One shifter piston under a pillar,
facing up, makes the whole pillar sink into the ground and rise again. A
comparator behind it reads how many blocks it holds, like a chest's fill
level: wait for a line to be all gone before the next thing moves.

The **Area Shifter** is an option, off unless a world turns it on
(`/gamerule phasecraft:area_shifters true`): it can switch a whole box of the
world off and on, which is more than a piston could ever do. Put two in the
hidden layer at opposite corners of a box (up to 16 x 16 x 16) and wire one
switch to both: while both are powered, everything in the box goes, a layer
at a time from the top, and when the power goes it comes back from the
bottom. Click a corner to set the speed (0 is all at once). Keep boxes apart:
each corner pairs with its nearest other one.

Station 21 is a vault: a chest built into a small wall. Click the brick
above it and the chest is gone, contents and all; click again and it is
back.

Station 22 is the giant iris door, on a raised platform (take the steps at
the front). Flip the lever beside it: a round hole opens in the middle of the
wall and grows outward, ring by ring, until the whole 7x7 doorway is open;
flip it back and it closes from the outside in. Every door block is held by
a hidden phase shifter, and all its wiring is hidden: turn the overlay on to
see how much of it there is.

Station 23 is a waterfall: five ribbons of water fall from a pool on a cliff
between rock ribs. Climb up and flip the lever on the pool's rim: the middle
ribbon stops, then the two beside it, then the outer two, each draining from
the top down, and a lit cave appears behind; flip it back and they pour again
from the outside in. A hidden phase shifter at the top of each ribbon puts a
stone back to stop it.

Station 24 is a mountain at the end of the line (x = 300 to 342): walk into
the gorge on its north side. Flip the lever left of the path: the
dark arched door goes like a clock hand sweeping round, from twelve o'clock
clockwise, and a tunnel to a vault full of gold appears; flip it back and
the door winds shut the other way.

Station 25 is a chasm past the mountain (x = 362 to 399): take the steps up
to the cliff and walk towards the edge. A stone bridge appears in front of
you, three blocks ahead, as you cross, and vanishes three blocks behind you;
nothing is there when nobody is near. Jumping is safe. Hidden strings at
head height over each step sense who is near.

Station 26 is a cliff past the bridge (x = 410 to 447): walk along the path
to its foot and climb. A stone staircase appears three steps ahead of you as
you go up to the ledge and its lookout, and vanishes behind you.

Station 27 is a sorting machine (x = 455 to 471): take the steps at the west
end up onto the wall, put items in the chest on top, and they come out sorted
into the chests below, one kind per chest (the frames show which: cobblestone,
dirt, oak logs, iron ingots), everything else in the last one. Nothing but the
wall and the chests is there: the sorter is in the hidden layer.

Station 28 is a castle tower (x = 543 to 547): flip the lever beside the path
to its door. The tower comes down block by block from the battlements in a
spiral, a course lower every side; flip the lever back and it builds itself up
again from the ground the same way. Flip it back while it is moving and it
turns around. A shifter piston under each column of the tower does it, all
hidden.

Station 29 is a vanishing cottage (x = 569 to 575): flip the lever in front of
its doorway and the whole cottage goes, roof first, layer by layer, leaving
its foundation; flip it back and it builds itself again from the floor, with
the bread, apples and iron still in its chest. Two hidden area shifters at its
corners do it. Area shifters only work with the game rule on
(`/gamerule phasecraft:area_shifters true`); the examples script turns it on.

Station 30 is a hole in the ocean (a pool at x = 587 to 599): flip the lever at
the pool's north edge and a glass-walled shaft opens down to the sand, dry;
flip it back and the sea closes over it. Two hidden area shifters bring the
walls (and a filler) back into the water, and hidden shifter pistons under the
sand take the filler out; closing, the same in reverse. Stand in the shaft as
it closes and the filler coming back lifts you up to the surface.

Station 31 is a light-up dance floor (x = 614 to 619): walk on it and the lamps
light under your feet, going out a second after you step off. Under each lamp
a hidden pressure plate on a phase block feels you, and a hidden phase port
lights the lamp; there is nothing else.

Station 32 is a combination lock (x = 627 to 641): four levers on a stone-brick
wall by the path. Find the setting that opens the door in the wall (it looks
like the rest of the wall) onto a small vault with a chest; any other setting
shuts it. (The answer: on, off, on, on, from west to east.) All the logic and
the door's machinery are hidden.

Station 33 is a pixel wall (x = 652 to 661, south of the path): a lime wall on a
platform. Go up the steps and flip the lever: a creeper's face appears on it,
eyes first; flip it back and the face goes. A hidden phase shifter at each
of the face's 20 pixels takes the lime block, and the black behind shows
through.

Station 34 is a treasure reveal (x = 686 to 700, south of the path): put
anything in the barrel on the altar and the treasure appears on the pedestals
behind it; take the offering back out and it goes. A hidden comparator reads
the barrel, and hidden phase shifters hold the treasure.

Station 35 is a minecart track (x = 714 to 759, south of the path, up the
steps): a chasm with rails on both cliffs and nothing between them. Put a
minecart on the powered rail by either wall, get in and press the button
beside it: the track is laid across the chasm ahead of you and taken up
behind you. Hidden shifter pistons hold each piece of the track; hidden strings
at rail height sense the cart.

Station 36 is a skeleton farm (x = 773 to 781, south of the path): a dark
tower with a skeleton spawner in a room at its top. Climb the stairs around it
to the gallery, watch through the window, and press the button: the room's
floor is gone for a second, the skeletons fall to the tower's foot and die,
and their bones and arrows turn up in the chest outside. The floor's
machinery and the loot's way to the chest are hidden. (Spawners make nothing
in Peaceful: set the difficulty to Easy or harder to see skeletons.)

![A dark stone room lit by a glowstone hidden in its far wall](images/phased-light-room.png)

Save and exit your test world, reopen it, and run `/phasecraft get` again to
manually inspect persistence. The same check runs automatically as
`./gradlew restartTest` (see below).

## Current limitations

Power crosses only through **phase ports**, and visible blocks are moved
only by **phase pistons** placed in the hidden layer (all in the Redstone
tab, or `/give @s phasecraft:phase_port`, `phasecraft:sticky_phase_piston`);
see docs/ARCHITECTURE.md. Build hidden machinery with editing mode (J), with
`/phasecraft set`, or from inside the hidden layer. Hidden machinery runs
wherever the visible world around it runs. Commands require operator permission level 2. Worlds from earlier builds
are upgraded automatically at server start.

Use a **new test world**, not your personal saves: the storage format has
no migration path yet.

## Tests and the build-to-play loop

| Command | What it does |
| --- | --- |
| `./gradlew build` | Compiles, then runs **every** test below (CI runs this). |
| `./gradlew runGameTest` | In-game tests: every class named `*GameTests` in `src/gametest`. |
| `./gradlew restartTest` | Full restart: one server writes hidden cells and shuts down, a second server process loads the same world and checks them. |
| `./gradlew clientTest` | A real game client, with OpenGL, with Vulkan, and with OpenGL plus Sodium: the overlay's pictures (screenshots in `build/run/clientGameTest*/screenshots`), keys and syncing, singleplayer and on a dedicated server, and editing mode driven by real key presses and clicks. |
| `./gradlew installToMinecraft` | `build`, then (only if everything passed) replaces PhaseCraft in your Minecraft mods folder. Restart Minecraft afterwards. |

`installToMinecraft` reads your mods folder from `local.properties`
(git-ignored), for example:

```properties
minecraftModsDir=/mnt/c/Users/<you>/AppData/Roaming/.minecraft/mods
```

Reports are written to `build/reports/gametest/TEST-*.xml`. Test servers run
headless; Fabric API's GameTest mode does not read `eula.txt`. The client
tests start a dedicated server too, for which the build accepts the
Minecraft EULA (`eula = true` in build.gradle; the project owner has
accepted it). A server you run yourself (for example for RCON) needs its own
`run/eula.txt`.

Test games never contact Mojang or Microsoft: service discovery and Realms
point at local addresses, test servers run in offline mode, and test clients
skip Mojang's blocked-servers list. (Dozens of test runs once got this
network's address blocked by Mojang, which broke real logins too.)

The client tests open a game window. If `Xvfb` is installed
(`sudo apt install xvfb`) they get a private virtual display, so nothing
appears on your screen; they render in software (Mesa), which needs
`libgl1-mesa-dri libegl1 mesa-vulkan-drivers`. Without Xvfb they use your
display.

## Local dev server with RCON

For checks in a real (non-test) world, run a local dedicated server from this
project and drive it over RCON:

```sh
./gradlew runServer                      # first run creates run/; accept the EULA in run/eula.txt
python3 tools/rcon.py "phasecraft get 10 64 10"
python3 tools/rcon.py "stop"
```

`run/` is git-ignored. Set these in `run/server.properties` before the first
real start: `enable-rcon=true`, a random `rcon.password`,
`server-ip=127.0.0.1` so neither the game port nor RCON is reachable from
other machines, and `pause-when-empty-seconds=0`: by default a server with
nobody online stops ticking after 60 seconds, and then nothing crosses
between layers (crossing happens on the next tick). `/phasecraft link` shows
the cross-layer counters; `pending` that never drains means no ticks. `tools/rcon.py` reads the host, port and password from that
file. Join from the Fabric 26.3 profile at `localhost`, with the same
PhaseCraft build in your mods folder (`./gradlew installToMinecraft`;
otherwise the server refuses the join over the new blocks).

**WSL2 (Windows):** in WSL's default NAT networking, Windows' `localhost`
does not reach a server bound to 127.0.0.1 inside WSL. Set `server-ip` to
WSL's own address (`ip -4 addr show eth0`, e.g. 172.23.x.x) and join at that
address; it is only reachable from this PC, and may change after a reboot.
Alternatively switch WSL to mirrored networking. Then, to use `/phasecraft`
in game, make yourself an operator with
`python3 tools/rcon.py "op <your name>"`.

### Examples world

`python3 tools/examples.py` builds a row of example stations near spawn on
the running server (best on a flat world: `level-type=minecraft\:flat`),
then reads every hidden cell back and exits 1 if one is wrong. Each station
has a sign. Press H to see the hidden blocks, or inspect them with
`/phasecraft get <x> -60 0` (or `execute in minecraft:the_nether run ...`
for station 5). Rerunning it rebuilds the area from scratch;
`python3 tools/examples.py --signs-only` rewrites just the signs. Station 10 is
the hidden 2x2 door; station 11 is the hidden staircase, on a raised
platform at x = 76 (climb the stairs on its north side; the lever is at the
top). Station 12 is the 3x3 flush door: walk into the tunnel at x = 97 and
flip the lever on its floor. The script operates all three (lever on, open;
lever off, closed) and leaves them closed for you.

To get screenshots of every station with the overlay on and off, without a
server:

```sh
python3 tools/examples.py --print-commands > build/examples-commands.txt
PHASECRAFT_EXAMPLES_COMMANDS=$PWD/build/examples-commands.txt ./gradlew runClientGameTest
```

The trailer is made the same way: `python3 tools/trailer.py` (about 10 minutes;
needs ffmpeg and Pillow) flies a scripted camera through the stations, the game
renders it a frame per game tick with the world frozen in between (`/tick
freeze`, `/tick step 1`), and the tool adds the title, captions and fades and
writes `build/trailer/phasecraft-trailer.mp4`. `--edit` re-edits the frames
already rendered. Change the shots in `shots()` in the tool.
