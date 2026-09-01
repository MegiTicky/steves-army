package com.stevesarmy.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

public class CallsignArgument implements ArgumentType<String> {
    private static final SimpleCommandExceptionType ERROR_INVALID = new SimpleCommandExceptionType(Component.literal("Invalid callsign, expected [a-z][a-z0-9_-]{0,15} or '-'"));

    public static CallsignArgument callsign() {
        return new CallsignArgument();
    }

    public static String getCallsign(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String word = reader.readUnquotedString();
        String lower = word.toLowerCase(Locale.ROOT);
        if (!lower.matches("[a-z][a-z0-9_-]{0,15}")) {
            reader.setCursor(start);
            throw ERROR_INVALID.createWithContext(reader);
        }
        return word;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        return builder.buildFuture();
    }
}
