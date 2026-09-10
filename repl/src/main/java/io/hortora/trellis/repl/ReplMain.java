package io.hortora.trellis.repl;

public final class ReplMain {

    public static void main(String[] args) {
        var config = ReplConfig.fromArgs(args);
        var app = new ReplApp(config);
        app.run();
    }
}
