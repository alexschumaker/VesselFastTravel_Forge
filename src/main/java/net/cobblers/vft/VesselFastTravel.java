package net.cobblers.vft;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sk89q.worldedit.forge.ForgePlayer;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.Location;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Timer;

import static com.sk89q.worldedit.forge.ForgeAdapter.adaptPlayer;
import static java.lang.Math.floor;

@Mod("vft")
public class VesselFastTravel {
    public VesselFastTravel() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    private VFTConfig config = new VFTConfig();
    private VesselTransport sailAction = null;
    private Queue queue = null;

    private static final Logger LOGGER = LogManager.getLogger();

    @SubscribeEvent
    public void onCommandEvent(CommandEvent event) throws CommandSyntaxException {
        LOGGER.info("Command Received.");

        ParseResults<CommandSource> parsedResults = event.getParseResults();
        String[] commandArgs = parsedResults.getReader().getString().split(" ");

        if (!(parsedResults.getContext().getSource().getEntity() instanceof ServerPlayerEntity)) {
            return;
        }

        if (Queue.commands.contains(commandArgs[0])) {
            if (queue != null) {
                event.setCanceled(true);
                if (!queue.getStatus().equals("READY") || commandArgs[0].toLowerCase().equals("/cancel")) {
                    queue.commandParser(event);
                    checkQueue();
                }
                return;
            } else {
                event.setCanceled(true);
                systemMsgToPlayer(parsedResults.getContext().getSource().asPlayer(), "No active voyage.");
                return;
            }
        }

        if (!commandArgs[0].equals("/vft") && !commandArgs[0].equals("/test")) {
            return;
        }

        ServerPlayerEntity sender = parsedResults.getContext().getSource().asPlayer();

        event.setCanceled(true);

        if (queue != null && queue.getStatus().equals("WAITING") && !commandArgs[1].toLowerCase().equals("sail")) {
            return;
        }

        if (queue != null && queue.getStatus().equals("READY")) {
            return;
        }

        commandHandler(sender, commandArgs);

        ForgePlayer sender2 = adaptPlayer(sender);
        Location pos = sender2.getBlockTrace(3);
        if (pos != null) {
            String msg = pos.getBlockX() + ", " + pos.getBlockY() + ", " + pos.getBlockZ();
            LOGGER.info(msg);
            systemMsgToPlayer(sender, msg);
        }
    }

    private void commandHandler(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs[0].equals("/test")) {
            LOGGER.info("Test Command Activated");
            String str = "String.vessel";
            LOGGER.info(str.split("\\."));
            new StructuralBlockList();
            return;
        }
        if (commandArgs.length < 2) {
            systemMsgToPlayer(sender, "Supported Commands: create, set, delete");
            return;
        }

        ForgePlayer player = adaptPlayer(sender);
        Location playerLoc = player.getLocation();
        String playerDir = player.getCardinalDirection().toString();

        // handle commands that are always available
        switch(commandArgs[1].toLowerCase()) {
            case "ls":
                listContent(sender, commandArgs);
                return;
        }

        // handle commands that are only available in the process of sailing or summoning; prevents other commands from being run in these situations
        if ((queue != null && queue.getStatus().equals("COMPLETED")) || (sailAction != null && sailAction.isSummon())) {
            switch(commandArgs[1].toLowerCase()) {
                case "undo":
                    undo(sender);
                    break;
                case "done":
                    finish(sender);
                    break;
                default:
                    systemMsgToPlayer(sender, "Finalize or undo the current voyage.");
            }
            return;
        }

        // handle commands that cannot be run during a sailing or summoning process
        switch(commandArgs[1].toLowerCase()) {
            case "create":
                createVessel(sender, commandArgs);
                break;
            case "set":
                setConstraint(sender, playerLoc, commandArgs);
                break;
            case "delete":
                deleteVessel(sender, commandArgs);
                break;
            case "harbor":
                createHarbor(sender, commandArgs);
                break;
            case "deleteharbor":
                deleteHarbor(sender, commandArgs);
                break;
            case "piece":
                createPiece(sender, commandArgs);
                break;
            case "deletepiece":
                deletePiece(sender, commandArgs);
                break;
            case "anchor":
                createAnchor(sender, commandArgs);
                break;
            case "deleteanchor":
                deleteAnchor(sender, commandArgs);
                break;
            case "type":
                setVesselType(sender, commandArgs);
                break;
            case "summon":
                summonVessel(sender, commandArgs);
                break;
            case "sail":
                initVoyage(sender, commandArgs);
                break;
            case "current":
                this.config.getVessel(commandArgs[2].toLowerCase()).setCurrentHarbor(commandArgs[3].toLowerCase());
                this.config.updateFile(commandArgs[2].toLowerCase());
                break;
            default:
                LOGGER.info("Unsupported Command.");
                systemMsgToPlayer(sender,"Unsupported Command.");
        }
    }

    private void createVessel(ServerPlayerEntity sender, String[] commandArgs) {
        ForgePlayer player = adaptPlayer(sender);
        BlockVector3 helmLocation = BlockVector3.at(player.getBlockTrace(3).getBlockX(), player.getBlockTrace(3).getBlockY(), player.getBlockTrace(3).getBlockZ());
        Direction playerDir = player.getCardinalDirection();

        if (helmLocation == null || !playerDir.isCardinal()) {
            systemMsgToPlayer(sender, "Stand targeting the helm, facing the front of the Vessel.");
            return;
        }
        if (commandArgs.length < 3) {
            systemMsgToPlayer(sender, "Specify a name for this Vessel.");
            return;
        }
        String vesselName = commandArgs[2];
        config.createVessel(vesselName, playerDir, helmLocation);
    }

    private void setConstraint(ServerPlayerEntity sender, Location playerLoc, String[] commandArgs) {
        if (commandArgs.length < 4) {
            systemMsgToPlayer(sender, "Missing Argument. /vft set <vesselName> <constraint>");
            return;
        }

        if (!Arrays.asList(config.constraints).contains(commandArgs[commandArgs.length-1].toUpperCase())) {
            systemMsgToPlayer(sender, "Malformed command. /vft set <vesselName> <partName> <constraint>");
            return;
        }

        String pieceArg = commandArgs.length == 5 ? commandArgs[3] : "base";

        if (config.setConstraint(commandArgs[2].toLowerCase(), pieceArg, commandArgs[commandArgs.length-1].toUpperCase(),
                BlockVector3.at(playerLoc.getBlockX(), playerLoc.getBlockY(), playerLoc.getBlockZ()))) {
            systemMsgToPlayer(sender, "The " + commandArgs[commandArgs.length-1].toUpperCase() + " end of " + commandArgs[2].toLowerCase() + " has been set successfully!");
        } else {
            systemMsgToPlayer(sender, "No vessel with this name exists or unable to set constraint. Use top, bottom, port, starboard, bow, or stern. Google it.");
        }
    }

    private void deleteVessel(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length < 3) {
            systemMsgToPlayer(sender, "Specify the name of the Vessel whose data you wish to delete.");
            return;
        }

        String vesselName = commandArgs[2].toLowerCase();
        if (config.deleteVessel(vesselName)) {
            systemMsgToPlayer(sender, "Successfully deleted data for " + vesselName + ".");
        } else {
            systemMsgToPlayer(sender, "Failed to delete data for " + vesselName + ".");
        }
    }

    private void createHarbor(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length < 4) {
            systemMsgToPlayer(sender, "Missing argument. /vft harbor <vesselName> <harborName> [anchorName]");
            return;
        }

        ForgePlayer player = adaptPlayer(sender);
        BlockVector3 helmLocation = BlockVector3.at(player.getBlockTrace(3).getBlockX(), player.getBlockTrace(3).getBlockY(), player.getBlockTrace(3).getBlockZ());
        Direction playerDir = player.getCardinalDirection();
        if (helmLocation == null || !playerDir.isCardinal()) {
            systemMsgToPlayer(sender, "Stand targeting the helm or anchor, facing way you'd like the vessel to face.");
            return;
        }

        String vesselName = commandArgs[2];
        String harborName = commandArgs[3];
        String anchorName = null;

        if (commandArgs.length == 5) {
            anchorName = commandArgs[4];
        }

        if (config.createHarbor(vesselName, harborName, playerDir, helmLocation, anchorName)) {
            systemMsgToPlayer(sender, "Harbor created sucessfully!");
        }
        else {
            systemMsgToPlayer(sender, "A harbor already exists with name " + harborName + ".");
        }
    }

    private void deleteHarbor(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length < 4) {
            systemMsgToPlayer(sender, "Invalid arguments. /vft deleteharbor <vesselName> <harborName>");
            return;
        }

        String vesselName = commandArgs[2].toLowerCase();
        String harborName = commandArgs[3].toLowerCase();
        if (config.deleteHarbor(vesselName, harborName)) {
            systemMsgToPlayer(sender, "Successfully deleted the " + harborName + " harbor data for " + vesselName + ".");
        } else {
            systemMsgToPlayer(sender, "Failed to delete the " + harborName + " harbor data for " + vesselName + ".");
        }
    }

    private void createAnchor(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length != 4) {
            systemMsgToPlayer(sender, "Invalid Arguments. /vft anchor <vesselName> <anchorName>");
            return;
        }

        ForgePlayer player = adaptPlayer(sender);
        BlockVector3 helmLocation = BlockVector3.at(player.getBlockTrace(3).getBlockX(), player.getBlockTrace(3).getBlockY(), player.getBlockTrace(3).getBlockZ());
        Direction playerDir = player.getCardinalDirection();
        if (helmLocation == null || !playerDir.isCardinal()) {
            systemMsgToPlayer(sender, "Stand targeting the anchor, facing way you'd like the anchor to be relative to the vessel.");
            return;
        }

        String vesselName = commandArgs[2];
        String anchorName = commandArgs[3];

        if (config.createAnchor(vesselName, anchorName, helmLocation, playerDir)) {
            systemMsgToPlayer(sender, "Anchor created sucessfully!");
        }
        else {
            systemMsgToPlayer(sender, "No vessel named " + vesselName + " exists.");
        }
    }

    private void deleteAnchor(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length < 4) {
            systemMsgToPlayer(sender, "Invalid arguments. /vft deleteAnchor <vesselName> <anchorName>");
            return;
        }

        String vesselName = commandArgs[2].toLowerCase();
        String anchorName = commandArgs[3].toLowerCase();
        if (config.deleteAnchor(vesselName, anchorName)) {
            systemMsgToPlayer(sender, "Successfully deleted the " + anchorName + " anchor data for " + vesselName + ".");
        } else {
            systemMsgToPlayer(sender, "Failed to delete the " + anchorName + " anchor data for " + vesselName + ".");
        }
    }

    private void createPiece(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length < 4) {
            systemMsgToPlayer(sender, "Missing argument. /vft piece <vesselName> <pieceName>");
            return;
        }

        String vesselName = commandArgs[2];
        String pieceName = commandArgs[3];

        if (config.createPiece(vesselName, pieceName)) {
            systemMsgToPlayer(sender, "Piece created sucessfully!");
        }
        else {
            systemMsgToPlayer(sender, "A piece already exists with name " + pieceName + ".");
        }
    }

    private void deletePiece(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length < 4) {
            systemMsgToPlayer(sender, "Invalid arguments. /vft deletePiece <vesselName> <pieceName>");
            return;
        }

        String vesselName = commandArgs[2].toLowerCase();
        String pieceName = commandArgs[3].toLowerCase();
        if (config.deletePiece(vesselName, pieceName)) {
            systemMsgToPlayer(sender, "Successfully deleted the " + pieceName + " harbor data for " + vesselName + ".");
        } else {
            systemMsgToPlayer(sender, "Failed to delete the " + pieceName + " harbor data for " + vesselName + ".");
        }
    }

    private void setVesselType(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length != 4) {
            systemMsgToPlayer(sender, "Invalid arguments. /vft type <vesselName> <air|sea|hybrid>");
            return;
        }

        if (!config.vessels.containsKey(commandArgs[2].toLowerCase())) {
            systemMsgToPlayer(sender, "No vessel named "+commandArgs[2].toLowerCase()+".");
            return;
        }

        if (!config.vessels.get(commandArgs[2].toLowerCase()).setType(commandArgs[3].toLowerCase())) {
            systemMsgToPlayer(sender, "Vessel type \""+commandArgs[3].toLowerCase()+"\" not supported.");
            return;
        }

        config.updateFile(commandArgs[2].toLowerCase());
        LOGGER.info(commandArgs[2]);
        LOGGER.info(config.vessels.get(commandArgs[2].toLowerCase()).type.getType());
        LOGGER.info(config.vessels.get(commandArgs[2].toLowerCase()));
    }

    private void initVoyage(ServerPlayerEntity sender, String[] commandArgs) {
        Vessel vessel = config.getVessel(commandArgs[2]);
        this.sailAction = new VesselTransport(vessel, commandArgs[3], sender, false);
        this.queue = new Queue(sender, vessel);

        if (sailAction.status.equals("ABORT") || queue.getStatus().equals("CANCELLED")) {
            sailAction = null;
            queue = null;
            return;
        }

        systemMsgToServer(sender, sender.getDisplayName().getString() + " wants to make a voyage to " + commandArgs[3] + ". To come along, climb aboard the " + commandArgs[2] + " and type /ready. Otherwise, type /pass.");

        checkQueue();
    }

    private void setSail() {
        sailAction.sail();
        Timer timer = new Timer();
        timer.schedule(new VoyageTimer(sailAction, queue), 0,1000);
    }

    private void systemMsgToPlayer(ServerPlayerEntity player, String msg) {
        player.sendMessage(new StringTextComponent(msg));
    }

    public void systemMsgToServer(ServerPlayerEntity initializer, String msg) {
        initializer.getServer().getPlayerList().sendMessage(new StringTextComponent(msg));
    }

    private void undo(ServerPlayerEntity player) {
        if (sailAction == null) {
            systemMsgToPlayer(player, "No current sailing route to undo.");
            return;
        }

        if (sailAction.status == "SUMMON_COMPLETE") {
            sailAction.removeDestination();
            sailAction = null;
            return;
        }

        if (sailAction.status.equals("DESTINATION")) {
            if (sailAction.returnPlayers(queue)) {
                sailAction.removeDestination();
                sailAction = null;
                queue = null;
            } else {
                systemMsgToServer(player, player.getDisplayName().getString() + " is trying to undo this Voyage, but can't until all players are on board.");
            }
        } else {
            systemMsgToPlayer(player, "Can't undo until after teleportation.");
        }
    }

    private void finish(ServerPlayerEntity player) {
        if (sailAction == null) {
            systemMsgToPlayer(player, "No voyage to finalize.");
            return;
        }
        systemMsgToPlayer(player, "Finalizing transport.");
        if (sailAction.status.equals("DESTINATION") || sailAction.status.equals("SUMMON_COMPLETE")) {
            sailAction.removeStart();
        }

        config.updateFile(sailAction.activeVesselName);
        sailAction = null;
        queue = null;
    }

    // Summons the specified vessel to the nearest harbor
    private void summonVessel(ServerPlayerEntity sender, String[] commandArgs) {
        if (sailAction != null) {
            systemMsgToPlayer(sender, "Voyage in progress; can't summon now!");
            return;
        }

        if (commandArgs.length != 3) {
            systemMsgToPlayer(sender, "Invalid format. /vft summon <vesselName>");
            return;
        } else if (!config.vessels.containsKey(commandArgs[2])) {
            systemMsgToPlayer(sender, "No vessel named " +commandArgs[2]+ " found.");
            return;
        }

        Vessel vessel = config.vessels.get(commandArgs[2].toLowerCase());
        VFTUtils.harborProximity prox = VFTUtils.getClosestHarbor(vessel, sender);

        if (prox.distance > 60) {
            systemMsgToPlayer(sender, "You are " + floor(prox.distance) + " blocks away from the nearest harbor. Must be within 60 to summon.");
            return;
        }

        sailAction = new VesselTransport(vessel, prox.harbor.name, sender, true);

        if (sailAction.status.equals("ABORT")) {
            sailAction = null;
            return;
        }

        sailAction.sail();
    }

    private void listContent(ServerPlayerEntity sender, String[] commandArgs) {
        if (commandArgs.length > 4 || commandArgs.length < 2) {
            systemMsgToPlayer(sender, "Invalid format. /vft ls | /vft ls vessels | /vft ls <vesselName> <harbors | pieces>");
            return;
        }

        if (commandArgs.length == 2) {
            Vessel currentVessel = VFTUtils.getBoardedVessel(config, sender);
            if (currentVessel != null) {
                systemMsgToPlayer(sender, "You are currently aboard \"" + currentVessel.name + "\".");
                systemMsgToPlayer(sender, " \"" + currentVessel.name + "\" is made up of these pieces: ");
                for(String piece : currentVessel.pieces.keySet()) {
                    systemMsgToPlayer(sender, "   - " + piece);
                }
                systemMsgToPlayer(sender, " \"" + currentVessel.name + "\" has these harbors: ");
                for(String harbor : currentVessel.harbors.keySet()) {
                    systemMsgToPlayer(sender, "   - " + harbor + (harbor.equals(currentVessel.currentHarbor.name) ? " <- current" : ""));
                }
            } else {
                systemMsgToPlayer(sender, "Cannot find boarded vessel. Either it hasn't been created or is misplaced (try using /vft current <vesselName> <harborName>).");
            }
        } else if (commandArgs[2].toLowerCase().equals("vessels")) {
            systemMsgToPlayer(sender, "Your Vessels: ");
            for(String vessel : config.vessels.keySet()) {
                systemMsgToPlayer(sender, "   - " + vessel);
            }
        } else if (config.vessels.containsKey(commandArgs[2].toLowerCase()) && commandArgs.length == 4) {
            String vesselName = commandArgs[2].toLowerCase();
            if (commandArgs[3].toLowerCase().equals("harbors")) {
                systemMsgToPlayer(sender, "Harbors for " + vesselName + ": ");
                for(String harbor : config.vessels.get(vesselName).harbors.keySet()) {
                    systemMsgToPlayer(sender, "   - " + harbor);
                }
            } else if (commandArgs[3].toLowerCase().equals("pieces")) {
                systemMsgToPlayer(sender, "Pieces for " + vesselName + ": ");
                for(String piece : config.vessels.get(vesselName).pieces.keySet()) {
                    systemMsgToPlayer(sender, "   - " + piece);
                }
            } else {
                systemMsgToPlayer(sender, "No vessel named " + vesselName);
            }
        } else {
            systemMsgToPlayer(sender, "Invalid argument. /vft ls vessels | /vft ls <vesselName> <harbors | pieces>");
        }
    }

    private void checkQueue() {
        // check queue.status and act accordingly
        String status = queue.getStatus();

        if (status.equals("CANCELLED") && sailAction.status.equals("START")) {
            queue = null;
            sailAction = null;
        } else if (status.equals("CANCELLED") && sailAction.status.equals("AWAITING_TIMER")) {
            sailAction.removeDestination();
            queue = null;
            sailAction = null;
        } else if (status.equals("READY")) {
            setSail();
        }
    }
}
// TODO schematic loading, better undo, glass pane testHarbor, nudging/anchor adaptation