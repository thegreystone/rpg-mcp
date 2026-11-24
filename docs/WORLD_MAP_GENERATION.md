# Creative world-map authoring brief

Status: creative guidance for the implemented Campaign/World Engine authoring workflow and its broader
generation targets. See [supported tool usage](MAP_USAGE.md), the
[delivery checklist](MAP_IMPLEMENTATION_PLAN.md),
[`DESIGN.md` §20](DESIGN.md#20-semantic-maps) and the [map format](MAP_FORMAT.md) for persistence,
spatial structure and atlas continuity. Generation tools should supply a concise version of this brief
to the authoring LLM together with the particular campaign and neighboring-map constraints.

## Purpose and inputs

Create a distinctive, attractive and believable fictional region that supports player-directed travel
and further world building. The initial region contains a few useful cities or villages and enough
extent for meaningful journeys. Further atlas sheets can be inhabited, sparsely settled, wilderness,
coast, islands or mostly ocean according to the developing geography.

The authoring context should include:

- Campaign premise, tone, fantasy style, content profile and any player-authored setting constraints.
- Established world facts, named places, terrain, journeys and location references that must survive.
- The requested region's physical extent, atlas position and shared boundary constraints from all
  existing neighbors, including coastlines and waterways.
- World size, geographic latitude range, requested/saved climate and season, plus relevant elevation,
  ocean/coastal and other established climate modifiers.
- The saved visual style/profile and any reference artwork or allowed custom materials.
- Opening-scenario needs for an initial map, or the incoming travel position/mode for an extension.
- What the party knows, what is reported and what should remain GM-only.
- For an older campaign, graph-analysis results, existing map fragments and sourced legacy constraints;
  author only missing compatible structure and retain unresolved contradictions explicitly.

The northern-road example in the design is illustrative. The workflow supports expansion in any
direction by road, cross-country, river or sea. Oceans, coastlines, islands and archipelagos are valid
creative choices at initial generation or in compatible unexplored parts of the growing atlas.

## Latitude and climate

Use a roughly Earth-sized world unless the campaign specifies otherwise. For an ordinary opening with
unspecified climate, choose the actual starting location around 43.5 degrees north, a southern-France
latitude reference. The local atlas origin is unrelated to the equator. An explicit tropical, cold,
southern-hemisphere or other climate premise takes precedence; choose fitting geographic coordinates
rather than retaining the default latitude and arbitrarily painting a different biome over it.

Consider distance from the equator in either hemisphere, elevation, moisture, ocean influence and
season together. Higher absolute latitude generally trends colder; southward travel from a northern
opening trends warmer until the equator and colder again toward the south pole. A forested mountain,
dry inland valley and mild coast can differ within the same latitude band. Let vegetation, farming,
snow, seasonal roads and narrative descriptions reflect the saved regional context. Weather can vary
without redefining the region's climate. Select broad, coherent tendencies rather than attempting a
full climate simulation, and save explanations for deliberate magical exceptions.

Neighboring sheets inherit latitude from the geographic transform and retain compatible climate at
their shared edges. Do not introduce a dramatic biome/temperature change merely because the party
crossed a sheet boundary. Existing-campaign facts constrain the opening anchor and override defaults.

Long journeys toward a pole can lead through colder woodland, tundra and eventually permanent ice,
with transitions shaped by latitude, elevation and regional conditions. Let these changes emerge over
appropriate world distances rather than placing every climate within a few neighboring sheets.

## Coastal opening preference

Prefer an initial region that borders a coast when it fits the campaign premise and player preferences.
This is a soft creative preference: an inland region remains a valid choice. The opening position can
be a coastal town or any other location that serves the campaign. Generate fitting names for each
campaign; use player-selected names when supplied as actual world-building choices.

An illustrative opening is:

> You're starting in the coastal town of Sarg, next to the Western Sea. This far south, the water is
> warm and accommodating.

In that example, place Sarg on the coast with the Western Sea to its west and retain the chosen warm
regional climate as campaign context. Let the surroundings support the description through suitable
shorelines, harbor activity, livelihoods and opportunities for land or sea travel. The narrative and
map should agree about the town's position, directions and environmental character. Other campaigns
can have different coastlines, climates and openings.

## Creative guidance

1. **Choose a geographic identity.** Begin with a recognizable arrangement: an estuary, broad valley,
   broken coast, island chain, inland basin, lake district or upland frontier. Give the region a coherent
   character instead of distributing the same list of features over every map.
2. **Shape land and water together.** Establish broad relief, shorelines, watercourses and natural
   passages. Give drainage a plausible relationship to uplands, basins and outlets. Coastlines can form
   bays, headlands, inlets and peninsulas. Features can continue beyond the current sheet.
3. **Let terrain form related areas.** Consider how water, elevation and exposure influence woodland,
   farmland, marsh, heath, rocky ground or other terrain appropriate to the setting. Use varied shapes
   and believable transitions. Choose the features that serve this region; no biome checklist is required.
4. **Give settlements reasons to exist.** Consider water, food, resources, sheltered landings, trade,
   defense, pilgrimage and local history. A town at a crossing, a mining village below a pass and a port
   serving an agricultural hinterland should relate to their surroundings and to one another. Vary their
   size and density, leaving some areas remote or empty.
5. **Make journeys meaningful.** Roads, tracks, passes, ferries and sea routes connect places for reasons.
   Place useful roadside stops around crossings, junctions and stopping distances appropriate to travel
   assumptions. Include alternatives, bottlenecks, difficult stretches and opportunities to leave the
   road. Maintain physical distances and avoid convenient connections between every destination.
6. **Show traces of history.** Old roads, abandoned workings, ruined defenses, reclaimed farmland or a
   displaced harbor can explain the present landscape. Choose a few details whose placement tells a
   story and supports later discovery, rather than attaching an unrelated ruin to every settlement.
7. **Make fantasy consequential.** An enchanted forest, floating island, impossible river or magical
   scar can depart from ordinary geography. Give the departure an in-world cause and consequences for
   habitation, livelihoods, travel or danger. Favor a coherent strange feature over arbitrary exceptions.
8. **Offer several directions for play.** Add distinctive landmarks, local tensions, rumors and quieter
   spaces. These create possibilities, not a required itinerary. Keep hidden information separate from
   the initial player map. Leave room to establish more compatible POIs as the campaign develops.
9. **Place the opening where the premise fits.** The player might begin in a port, village, crowded
   district, remote shelter, ruin, seasonal camp, on an island or aboard a vessel. Record its actual
   location/position and initial view; the atlas origin or map center does not dictate the starting point.
10. **Compose a readable map.** Use a clear hierarchy of terrain, routes, settlements and labels. Vary
    silhouettes, settlement clusters and open space. Use symbols, typography and decoration consistent
    with the saved visual style, with label density appropriate to the map's scale. Detail should help
    readers understand the region as well as make it attractive.

These are creative prompts and review heuristics, not mandatory terrain quotas or a full Earth simulation.
Campaign preferences and intentional fantasy geography can override a conventional pattern while
retaining a comprehensible world and consistent gameplay structure.

## Opening settlements, roads and the story

Create the opening region's initially relevant cities, towns, villages and key road network during
campaign setup. Establish their names, positions, relationships and route distances before gameplay;
interiors and minor sites can acquire detail later. Consider what the opening story needs: a town's
dependence on a port, a disputed crossing, a remote community, competing trade interests or a historical
connection can give places and routes narrative meaning. Preserve several possible directions of play.

Normally extend the main road through one or more land boundaries of the initial map. Show an actual
road reaching the edge and save its boundary crossing; a named or rumored onward destination can make
the world feel larger without pre-generating the next region. An island entirely enclosed by water can
link its roads to harbors and outward sea routes instead. Subsequent sheets need not repeat the initial
settlement inventory or carry roads through every edge.

Establish relevant travel dangers and their world/story causes: hostile borders, bandit country,
contested roads, local patrols or recently disrupted protection. Give the DM suitable encounter
opportunities and narrative locations while preserving freedom to place an encounter or roll for a
valid point along a journey. Let player precautions and changing world conditions affect risk; a
random local danger can fit the setting without becoming another mandatory central-plot revelation.

## Hills, mountains and believable roads

Give the opening region hills and mountains appropriate to the setting, subject to explicit campaign
constraints. Shape them as related uplands, ridges, valleys and passes, with geography that affects
settlement and travel. Later sheets can have different terrain, including lowlands or open sea.

Before finalizing roads, consider the routes a traveler or road builder would reasonably choose:

- Follow usable valleys, gentler ground or a practical route around a ridge where available.
- Where the ridge blocks useful travel or going around it creates a substantial detour, identify a
  plausible pass or other crossing. Place the road through that corridor with matching terrain geometry.
- Give an unusual steep road, tunnel, engineered crossing or magical route a purpose and an explanation
  consistent with the setting, such as access to a mine, defense or an older trade connection.
- Put bridges and roadside services where crossings, route junctions and stopping needs make sense.
  A pass can explain an inn, toll station, fortification or seasonal concern without requiring them all.
- Preserve route lengths and stated traversal constraints. Use available elevation data for gradient
  checks; otherwise review qualitative relief and avoid claiming measurements absent from the data.

The map's mountain symbols must leave the intended valley/pass legible. Road endpoints, pass anchors,
relative elevations and movement queries should describe the same geography. The generation review
should explain why a main road crosses a mountain barrier when an easier bypass appears available.

## Oceans, coasts and islands

Use bodies of water as geographic structure and travel space. A regional sheet can include open sea,
coastal settlements, offshore islands, an inland lake island or an archipelago. Favor distinctive island
shapes and relationships over making each sheet into its own isolated island.

Distinguish open water, land, connected channels and navigable routes explicitly. A peninsula cropped
by the image edge is not necessarily an island. Preserve shoreline/land-water continuity, river mouths,
crossings and established sea routes across neighboring sheets. Where appropriate, bays, reefs, cliffs,
beaches, exposed crossings or sheltered landings can create useful destinations and travel choices.
Select them for the coast's character rather than adding every maritime feature automatically.

New islands and ocean regions must fit already committed geography and journeys. A vessel can enter
an adjoining sea sheet under the same atlas expansion mechanism as a land journey. New content can
span several sheets while retaining shared feature identities and the initial cartographic style.

## Contrasting opening-region examples

These illustrate variety; choose or invent a premise suited to the campaign:

- **Drowned causeways:** a shallow archipelago contains inhabited high ground, old roads exposed by
  falling water and trading villages linked by boat. Begin aboard a stranded courier vessel.
- **An inland basin:** orchards and small farms surround springs and a lake; an abandoned mining road
  crosses the enclosing ridge. Begin at a remote shelter after travelers fail to return from the pass.
- **A disputed estuary:** marsh channels, farmland, river traffic and competing ferry landings support
  a port and nearby towns. Begin on a grain barge detained at a contested toll station.
- **A broken northern coast:** sheltered inlets support scattered communities and accessible valleys
  lead inland. Begin at a seasonal gathering beside a newly exposed ruin.
- **An old frontier road:** a market town and smaller villages trade through woodland and hill country,
  while an abandoned bridge makes an older route uncertain. Begin at an inn as conflicting news arrives.
- **A volcanic island:** a protected harbor, terraced uplands and a distant lighthouse connect fishing
  villages and a busy port. Begin in a hillside settlement preparing for an unexpected arrival by sea.

## Authoring output and review

Produce the structured proposal required by the generation tool, accompanied by a concise explanation:

- Geographic premise and visual character, with major terrain and land/water relationships.
- Geographic placement and regional climate, including the opening latitude and relevant modifiers.
- Key settlements/landmarks, their purpose, proposed positions and relevant relationships.
- The initial key road network, crossings, physical distances, travel assumptions and known barriers,
  including outward main-road continuations or appropriate island/sea connections.
- Hills, mountains, valleys and pass anchors, with the rationale for important ridge crossings/bypasses.
- Starting location/position and initial party knowledge, or continuity with the incoming journey.
- Edge continuations and constraints for later atlas expansion; distinguish established facts from
  revisable creative intentions about ungenerated areas.
- A few optional discovery/adventure opportunities and any intentional fantasy departures.

Use existing references where available and the tool's draft-key conventions for new entities; the
engine assigns canonical identities. Let the engine control texture/output pixel sizing and validate
geometry. Commit the actual accepted geography and creative choices for reuse rather than asking the
next model to recreate the region from prose or a seed.

Propose meaningful road/river bends and terrain boundaries as structured geometry, not only endpoints
or adjectives. The engine can resolve/smooth a coarse proposal, validate crossings and commit the
accepted paths once. A later request for another style or level of drawing detail reuses those paths;
new geographic detail must fit their saved anchors and declared precision.

Before accepting the draft, briefly review:

| Question | Useful evidence |
| --- | --- |
| Is it connected? | Land/water routes, barriers and distances explain travel opportunities. |
| Does the climate fit? | Latitude, elevation, coastal influence and season support the landscape and narration; neighboring sheets transition coherently. |
| Do roads fit the relief? | Ridge bypasses and passes are spatially plausible; routes and rendered terrain agree. |
| Does it feel inhabited? | Important settlements have purposes and relationships with their surroundings. |
| Is it distinctive? | Geography and landmarks give the region an identity even without its labels. |
| Is it open to play? | Several directions, quieter spaces and further world building remain possible. |
| Does the opening fit the story? | Initial settlements and roads support its situation without imposing an itinerary. |
| Does it fit? | Existing neighbors, committed facts, opening needs and saved visual style agree. |
| Is it readable? | Scale, symbols, labels and decorative detail support an understandable map. |

The engine handles spatial validation and persistence; the creative review improves the proposal's
coherence and presentation. Both initial creation and later expansion use the same authoring guidance.
