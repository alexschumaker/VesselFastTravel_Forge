package net.cobblers.vft;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sk89q.worldedit.forge.ForgePlayer;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.RegionIntersection;
import com.sk89q.worldedit.util.Location;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.ForgeConfig;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.tools.picocli.CommandLine;

import java.util.*;

import static com.sk89q.worldedit.forge.ForgeAdapter.adaptPlayer;

public class Queue {
    private static final Logger LOGGER = LogManager.getLogger();
    public static List<String> commands = Arrays.asList("/ready", "/cancel", "/pass");
    private PlayerList playerList;
    private ServerPlayerEntity captain;
    private Vessel vessel;

    private int awaitingResponse = 0;
    private Map<String, ServerPlayerEntity> readyPlayers = new HashMap<>();
    private Map<String, ServerPlayerEntity> passPlayers = new HashMap<>();

    private boolean cancelled = false;
    private boolean completed = false;

    public Queue(ServerPlayerEntity captain, Vessel vessel) {
        playerList = captain.getServer().getPlayerList();
        this.captain = captain;
        this.vessel = vessel;

        readyPlayers.put(captain.getDisplayName().getString(), captain);
        this.awaitingResponse = playerList.getCurrentPlayerCount() - 1;

        if (!checkCaptainAtHelm()) {
            systemMsgToPlayer(captain, "Must be at the helm of the vessel you wish to sail.");
            cancelled = true;
        }
    }

    public void commandParser(CommandEvent event) throws CommandSyntaxException {
        LOGGER.info("Queue Command Received.");

        ParseResults<CommandSource> parsedResults = event.getParseResults();
        String[] commandArgs = parsedResults.getReader().getString().split(" ");

        event.setCanceled(true);
        ServerPlayerEntity sender = parsedResults.getContext().getSource().asPlayer();

        if (completed) {
            systemMsgToPlayer(sender,"Voyage has already been completed. Inspect your vessel to make sure everything is good and send </vft done>. If you find an issue, return using </vft undo>.");
            return;
        }

        switch(commandArgs[0]) {
            case "/ready":
                playerReady(sender);
                break;
            case "/cancel":
                playerCancel(sender);
                break;
            case "/pass":
                playerPass(sender);
                break;
        }
    }

    private void playerReady(ServerPlayerEntity sender) {
        String name = sender.getDisplayName().getString();
        if (!readyPlayers.containsKey(name)) {
            if (sender.getDisplayName().getString().equals(captain.getDisplayName().getString())) {
                if (!checkCaptainAtHelm()) {
                    systemMsgToPlayer(sender, "The captain must be at the helm!");
                    return;
                }
            } else if (!checkPlayerOnBoard(sender)) {
                systemMsgToPlayer(sender, "You must board the vessel!");
                return;
            }
            if (passPlayers.containsKey(name)) {
                passPlayers.remove(name);
                awaitingResponse += 1;
            }
            readyPlayers.put(name, sender);
            awaitingResponse -= 1;

            systemMsgToPlayer(sender, "Ready!");
            systemMsgToServer(name + " is ready! " + (playerList.getCurrentPlayerCount() - awaitingResponse) + "/" + (playerList.getCurrentPlayerCount() - passPlayers.size()));
        }
    }

    private void playerCancel(ServerPlayerEntity sender) {
        systemMsgToServer(sender.getDisplayName().getString() + " has cancelled the crew's voyage plans.");
        this.cancelled = true;
    }

    private void playerPass(ServerPlayerEntity sender) {
        String name = sender.getDisplayName().getString();

        if (sender.getDisplayName().getString().equals(captain.getDisplayName().getString())) {
            systemMsgToPlayer(sender, "You cannot pass on your own voyage!");
            return;
        }

        if (!passPlayers.containsKey(name)) {
            if (readyPlayers.containsKey(name)) {
                readyPlayers.remove(name);
                awaitingResponse += 1;
            }

            passPlayers.put(name, sender);
            awaitingResponse -= 1;

            systemMsgToPlayer(sender, "Passed...");
            systemMsgToServer(name + " has passed. " + (playerList.getCurrentPlayerCount() - awaitingResponse) + "/" + (playerList.getCurrentPlayerCount() - passPlayers.size()));
        }
    }

    public void complete() {
        completed = true;
    }

    public String getStatus() {
        if (cancelled) {
            return "CANCELLED";
        } else if (completed) {
            return "COMPLETED";
        } else if (awaitingResponse == 0) {
            return "READY";
        } else {
            return "WAITING";
        }
    }

    public ServerPlayerEntity getCaptain() {
        return captain;
    }

    public void resetQueue() {
        playerList = captain.getServer().getPlayerList();
        awaitingResponse = playerList.getCurrentPlayerCount();

        readyPlayers = new HashMap<>();
        passPlayers = new HashMap<>();

        systemMsgToServer("Not all sailors on board! The queue has been reset. (0/"+ playerList.getCurrentPlayerCount() + " ready)");
    }

    public Collection<ServerPlayerEntity> getConfirmedPlayers() {
        return readyPlayers.values();
    }

    public boolean checkPlayerOnBoard(ServerPlayerEntity player) {
        RegionIntersection region = new RegionIntersection(this.vessel.currentHarbor.world, this.vessel.getPiecesList(this.vessel.currentHarbor.world, this.vessel.currentHarbor));
        return region.contains(BlockVector3.at(player.getPosition().getX(), player.getPosition().getY(), player.getPosition().getZ()));
    }

    public boolean checkCaptainAtHelm() {
        return (vessel.currentHarbor.helmLocation.distance(BlockVector3.at(captain.posX, captain.posY, captain.posZ)) < 3);
    }

    private void systemMsgToPlayer(ServerPlayerEntity player, String msg) {
        player.sendMessage(new StringTextComponent(msg));
    }

    public void systemMsgToServer(String msg) {
        playerList.sendMessage(new StringTextComponent(msg));
    }
}
