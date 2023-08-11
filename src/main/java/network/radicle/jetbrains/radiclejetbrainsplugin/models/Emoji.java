package network.radicle.jetbrains.radiclejetbrainsplugin.models;

import java.util.List;

public class Emoji {
    private final String name;
    private final String unicode;

    public Emoji(String name, String unicode) {
        this.name = name;
        this.unicode = unicode;
    }

    public String getName() {
        return name;
    }

    public String getUnicode() {
        return unicode;
    }

    public static List<Emoji> loadEmojis() {
        return List.of(
                new Emoji("Thumbs Up", "\uD83D\uDC4D"), // 👍
                new Emoji("Thumbs Down", "\uD83D\uDC4E"), // 👎
                new Emoji("Smiley Face", "\uD83D\uDE04"), // 😄
                new Emoji("Tada", "\uD83C\uDF89"), // 🎉
                new Emoji("Thinking Face", "\uD83D\uDE15"), // 😕
                new Emoji("Heart", "\u2764"), // ❤
                new Emoji("Rocket", "\uD83D\uDE80"), // 🚀
                new Emoji("Eyes", "\uD83D\uDC40") // 👀
        );
    }
}
