package dev.phasecraft.test;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/**
 * Runs a command through the server's normal command pipeline, as if typed in
 * chat, and records the success flag and the chat lines it sent back.
 */
final class CommandProbe {
    /** What one command did: whether it succeeded, and the chat lines it produced. */
    record Result(boolean succeeded, List<String> messages) {
        String lastMessage() {
            return messages.isEmpty() ? "<no message>" : messages.getLast();
        }
    }

    private CommandProbe() {}

    static Result run(ServerLevel level, LevelBasedPermissionSet permissions, String command) {
        List<String> messages = new ArrayList<>();
        boolean[] succeeded = {false};
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                messages.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = level.getServer().createCommandSourceStack()
                .withLevel(level)
                .withSource(capture)
                .withPermission(permissions)
                .withCallback((success, result) -> succeeded[0] = success && result > 0);
        level.getServer().getCommands().performPrefixedCommand(source, command);
        return new Result(succeeded[0], List.copyOf(messages));
    }

    /** Runs a command as a player (op level 2), capturing what it sends back. */
    static Result asPlayer(ServerPlayer player, String command) {
        List<String> messages = new ArrayList<>();
        boolean[] succeeded = {false};
        CommandSourceStack source = player.createCommandSourceStack()
                .withSource(capturing(messages))
                .withPermission(LevelBasedPermissionSet.GAMEMASTER)
                .withCallback((success, result) -> succeeded[0] = success && result > 0);
        player.level().getServer().getCommands().performPrefixedCommand(source, command);
        return new Result(succeeded[0], List.copyOf(messages));
    }

    private static CommandSource capturing(List<String> messages) {
        return new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                messages.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
    }

    static Result asOperator(ServerLevel level, String command) {
        return run(level, LevelBasedPermissionSet.GAMEMASTER, command);
    }
}
