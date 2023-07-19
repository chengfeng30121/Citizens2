package net.citizensnpcs.trait.waypoint;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import ch.ethz.globis.phtree.PhDistanceL;
import ch.ethz.globis.phtree.PhFilterDistance;
import ch.ethz.globis.phtree.PhTree;
import ch.ethz.globis.phtree.PhTree.PhKnnQuery;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Goal;
import net.citizensnpcs.api.ai.GoalSelector;
import net.citizensnpcs.api.astar.AStarGoal;
import net.citizensnpcs.api.astar.AStarMachine;
import net.citizensnpcs.api.astar.AStarNode;
import net.citizensnpcs.api.astar.Agent;
import net.citizensnpcs.api.astar.Plan;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.citizensnpcs.trait.waypoint.WaypointProvider.EnumerableWaypointProvider;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.Util;

/**
 * Stores guided waypoint info. Guided waypoints are a list of {@link Waypoint}s that will be navigated between
 * randomly. Helper waypoints can be used to guide navigation between the random waypoints i.e. navigating between guide
 * waypoints. For example, you might have a "realistic" NPC that walks between houses using helper waypoints placed
 * along the roads.
 */
public class GuidedWaypointProvider implements EnumerableWaypointProvider {
    private GuidedAIGoal currentGoal;
    private final List<Waypoint> destinations = Lists.newArrayList();
    private float distance = -1;
    private final List<Waypoint> guides = Lists.newArrayList();
    private NPC npc;
    private boolean paused;
    private final PhTree<Waypoint> tree = PhTree.create(3);
    private final PhTree<Waypoint> treePlusDestinations = PhTree.create(3);

    public void addDestination(Waypoint waypoint) {
        destinations.add(waypoint);
        rebuildTree();
    }

    public void addDestinations(Collection<Waypoint> waypoint) {
        destinations.addAll(waypoint);
        rebuildTree();
    }

    public void addGuide(Waypoint helper) {
        guides.add(helper);
        rebuildTree();
    }

    public void addGuides(Collection<Waypoint> helper) {
        guides.addAll(helper);
        rebuildTree();
    }

    @Override
    public WaypointEditor createEditor(final CommandSender sender, CommandContext args) {
        if (!(sender instanceof Player)) {
            Messaging.sendErrorTr(sender, CommandMessages.MUST_BE_INGAME);
            return null;
        }
        final Player player = (Player) sender;
        return new WaypointEditor() {
            private final EntityMarkers<Waypoint> markers = new EntityMarkers<Waypoint>();
            private boolean showPath = true;

            @Override
            public void begin() {
                if (showPath) {
                    createWaypointMarkers();
                }
                Messaging.sendTr(player, Messages.GUIDED_WAYPOINT_EDITOR_BEGIN);
            }

            private void createWaypointMarkers() {
                for (Waypoint waypoint : waypoints()) {
                    createWaypointMarkerWithData(waypoint);
                }
            }

            private void createWaypointMarkerWithData(Waypoint element) {
                Entity entity = markers.createMarker(element, element.getLocation().clone().add(0, 1, 0));
                if (entity == null)
                    return;
                ((NPCHolder) entity).getNPC().data().setPersistent("waypointhashcode", element.hashCode());
            }

            @Override
            public void end() {
                Messaging.sendTr(player, Messages.GUIDED_WAYPOINT_EDITOR_END);
                markers.destroyMarkers();
            }

            @EventHandler(ignoreCancelled = true)
            public void onPlayerChat(AsyncPlayerChatEvent event) {
                if (!event.getPlayer().equals(sender))
                    return;
                if (event.getMessage().equalsIgnoreCase("toggle path")) {
                    event.setCancelled(true);
                    Bukkit.getScheduler().scheduleSyncDelayedTask(CitizensAPI.getPlugin(), () -> togglePath());
                } else if (event.getMessage().equalsIgnoreCase("clear")) {
                    event.setCancelled(true);
                    Bukkit.getScheduler().scheduleSyncDelayedTask(CitizensAPI.getPlugin(), () -> {
                        destinations.clear();
                        guides.clear();
                        if (showPath) {
                            markers.destroyMarkers();
                        }
                    });
                } else if (event.getMessage().startsWith("distance ")) {
                    event.setCancelled(true);
                    double d = Double.parseDouble(event.getMessage().replace("distance ", "").trim());
                    if (d <= 0)
                        return;
                    Bukkit.getScheduler().scheduleSyncDelayedTask(CitizensAPI.getPlugin(), () -> {
                        distance = (float) d;
                        Messaging.sendTr(sender, Messages.GUIDED_WAYPOINT_EDITOR_DISTANCE_SET, d);
                    });
                }
            }

            @EventHandler(ignoreCancelled = true)
            public void onPlayerInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().equals(player) || event.getAction() == Action.PHYSICAL
                        || event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK
                        || event.getClickedBlock() == null || Util.isOffHand(event))
                    return;
                if (event.getPlayer().getWorld() != npc.getEntity().getWorld())
                    return;
                event.setCancelled(true);
                Location at = event.getClickedBlock().getLocation();
                for (Waypoint waypoint : waypoints()) {
                    if (waypoint.getLocation().equals(at)) {
                        Messaging.sendTr(player, Messages.GUIDED_WAYPOINT_EDITOR_ALREADY_TAKEN);
                        return;
                    }
                }
                Waypoint element = new Waypoint(at);
                if (player.isSneaking()) {
                    destinations.add(element);
                    Messaging.sendTr(player, Messages.GUIDED_WAYPOINT_EDITOR_ADDED_AVAILABLE);
                } else {
                    guides.add(element);
                    Messaging.sendTr(player, Messages.GUIDED_WAYPOINT_EDITOR_ADDED_GUIDE);
                }
                if (showPath) {
                    createWaypointMarkerWithData(element);
                }
                rebuildTree();
            }

            @EventHandler(ignoreCancelled = true)
            public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
                NPC clicked = CitizensAPI.getNPCRegistry().getNPC(event.getRightClicked());
                if (clicked == null || Util.isOffHand(event))
                    return;
                Integer hashcode = clicked.data().get("waypointhashcode");
                if (hashcode == null)
                    return;
                Iterator<Waypoint> itr = waypoints().iterator();
                while (itr.hasNext()) {
                    Waypoint point = itr.next();
                    if (point.hashCode() == hashcode) {
                        markers.removeMarker(point);
                        itr.remove();
                        break;
                    }
                }
            }

            private void togglePath() {
                showPath = !showPath;
                if (showPath) {
                    createWaypointMarkers();
                    Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_SHOWING_MARKERS);
                } else {
                    markers.destroyMarkers();
                    Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_NOT_SHOWING_MARKERS);
                }
            }
        };
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void load(DataKey key) {
        DataKey dd = key.keyExists("availablewaypoints") ? key.getRelative("availablewaypoints")
                : key.getRelative("destinations");
        for (DataKey root : dd.getIntegerSubKeys()) {
            Waypoint waypoint = PersistenceLoader.load(Waypoint.class, root);
            if (waypoint == null)
                continue;
            destinations.add(waypoint);
        }
        DataKey gd = key.keyExists("helperwaypoints") ? key.getRelative("helperwaypoints") : key.getRelative("guides");
        for (DataKey root : gd.getIntegerSubKeys()) {
            Waypoint waypoint = PersistenceLoader.load(Waypoint.class, root);
            if (waypoint == null)
                continue;
            guides.add(waypoint);
        }
        if (key.keyExists("distance")) {
            distance = (float) key.getDouble("distance");
        }
        rebuildTree();
    }

    @Override
    public void onRemove() {
        if (currentGoal == null)
            return;
        currentGoal.onProviderChanged();
        npc.getDefaultGoalController().removeGoal(currentGoal);
        currentGoal = null;
    }

    @Override
    public void onSpawn(NPC npc) {
        this.npc = npc;
        if (currentGoal == null) {
            currentGoal = new GuidedAIGoal();
            npc.getDefaultGoalController().addGoal(currentGoal, 1);
        }
    }

    private void rebuildTree() {
        tree.clear();
        treePlusDestinations.clear();
        for (Waypoint waypoint : guides) {
            tree.put(new long[] { waypoint.getLocation().getBlockX(), waypoint.getLocation().getBlockY(),
                    waypoint.getLocation().getBlockZ() }, waypoint);
            treePlusDestinations.put(new long[] { waypoint.getLocation().getBlockX(),
                    waypoint.getLocation().getBlockY(), waypoint.getLocation().getBlockZ() }, waypoint);
        }
        for (Waypoint waypoint : destinations) {
            treePlusDestinations.put(new long[] { waypoint.getLocation().getBlockX(),
                    waypoint.getLocation().getBlockY(), waypoint.getLocation().getBlockZ() }, waypoint);
        }
        if (currentGoal != null) {
            currentGoal.onProviderChanged();
        }
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("availablewaypoints");
        DataKey root = key.getRelative("destinations");
        for (int i = 0; i < destinations.size(); ++i) {
            PersistenceLoader.save(destinations.get(i), root.getRelative(i));
        }
        key.removeKey("helperwaypoints");
        root = key.getRelative("guides");
        for (int i = 0; i < guides.size(); ++i) {
            PersistenceLoader.save(guides.get(i), root.getRelative(i));
        }
        if (distance != -1) {
            key.setDouble("distance", distance);
        }
    }

    @Override
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (currentGoal != null) {
            currentGoal.onProviderChanged();
        }
    }

    /**
     * Returns destination and guide waypoints.
     */
    @Override
    public Iterable<Waypoint> waypoints() {
        return Iterables.concat(destinations, guides);
    }

    private class GuidedAIGoal implements Goal {
        private GuidedPlan plan;

        public void onProviderChanged() {
            if (plan == null)
                return;
            reset();
            if (npc.getNavigator().isNavigating()) {
                npc.getNavigator().cancelNavigation();
            }
        }

        @Override
        public void reset() {
            plan = null;
        }

        @Override
        public void run(GoalSelector selector) {
            if (plan == null || plan.isComplete()) {
                selector.finish();
                return;
            }

            if (npc.getNavigator().isNavigating())
                return;

            Waypoint current = plan.getCurrentWaypoint();
            npc.getNavigator().setTarget(current.getLocation());
            npc.getNavigator().getLocalParameters().addSingleUseCallback(cancelReason -> {
                if (plan != null) {
                    plan.update(npc);
                }
            });
        }

        @Override
        public boolean shouldExecute(GoalSelector selector) {
            if (paused || destinations.size() == 0 || !npc.isSpawned() || npc.getNavigator().isNavigating())
                return false;

            Waypoint target = destinations.get(Util.getFastRandom().nextInt(destinations.size()));
            plan = ASTAR.runFully(new GuidedGoal(target), new GuidedNode(null, new Waypoint(npc.getStoredLocation())));
            return plan != null;
        }
    }

    private static class GuidedGoal implements AStarGoal<GuidedNode> {
        private final Waypoint dest;

        public GuidedGoal(Waypoint dest) {
            this.dest = dest;
        }

        @Override
        public float g(GuidedNode from, GuidedNode to) {
            return (float) from.distance(to.waypoint);
        }

        @Override
        public float getInitialCost(GuidedNode node) {
            return h(node);
        }

        @Override
        public float h(GuidedNode from) {
            return (float) from.distance(dest);
        }

        @Override
        public boolean isFinished(GuidedNode node) {
            return node.waypoint.equals(dest);
        }
    }

    private class GuidedNode extends AStarNode {
        private final Waypoint waypoint;

        public GuidedNode(GuidedNode parent, Waypoint waypoint) {
            super(parent);
            this.waypoint = waypoint;
        }

        @Override
        public Plan buildPlan() {
            return new GuidedPlan(this.<GuidedNode> orderedPath());
        }

        public double distance(Waypoint dest) {
            return waypoint.distance(dest);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            GuidedNode other = (GuidedNode) obj;
            if (waypoint == null) {
                if (other.waypoint != null) {
                    return false;
                }
            } else if (!waypoint.equals(other.waypoint)) {
                return false;
            }
            return true;
        }

        @Override
        public Iterable<AStarNode> getNeighbours() {
            PhFilterDistance filter = new PhFilterDistance();
            filter.set(
                    new long[] { waypoint.getLocation().getBlockX(), waypoint.getLocation().getBlockY(),
                            waypoint.getLocation().getBlockZ() },
                    PhDistanceL.THIS, distance == -1 ? npc.getNavigator().getDefaultParameters().range() : distance);
            PhTree<Waypoint> source = getParent() == null ? tree : treePlusDestinations;
            PhKnnQuery<Waypoint> res = source.nearestNeighbour(100, PhDistanceL.THIS, filter,
                    waypoint.getLocation().getBlockX(), waypoint.getLocation().getBlockY(),
                    waypoint.getLocation().getBlockZ());
            List<AStarNode> neighbours = Lists.newArrayList();
            res.forEachRemaining(n -> neighbours.add(new GuidedNode(this, n)));
            return neighbours;
        }

        @Override
        public int hashCode() {
            return 31 + ((waypoint == null) ? 0 : waypoint.hashCode());
        }
    }

    private static class GuidedPlan implements Plan {
        private int index = 0;
        private final Waypoint[] path;

        public GuidedPlan(Iterable<GuidedNode> path) {
            this.path = Iterables.toArray(Iterables.transform(path, to -> to.waypoint), Waypoint.class);
        }

        public Waypoint getCurrentWaypoint() {
            return path[index];
        }

        @Override
        public boolean isComplete() {
            return index >= path.length;
        }

        @Override
        public void update(Agent agent) {
            index++;
        }
    }

    private static final AStarMachine<GuidedNode, GuidedPlan> ASTAR = AStarMachine.createWithDefaultStorage();
}
