# rdp4j

`rdp4j` is the standalone Maven project containing Tomato's RDP client,
rendering, clipboard, audio, keyboard mapping, NLA, and TLS implementation.

## Build and install locally

```shell
../mvnw -f pom.xml clean install
```

On Windows:

```powershell
..\mvnw.cmd -f pom.xml clean install
```

The artifact coordinates are:

```xml
<dependency>
    <groupId>com.tangluobo</groupId>
    <artifactId>rdp4j</artifactId>
    <version>1.0.5</version>
</dependency>
```

The frontend is selected explicitly through `RdpFrontend`. `SwingRdpFrontend`
retains the original Swing canvas and input path, while `FxRdpFrontend` uses a
JavaFX `WritableImage`/`ImageView` and sends JavaFX key and mouse events directly
as RDP input messages. `RdpPane` selects the pure JavaFX frontend and does not
embed Swing.

The JAR declares the automatic JPMS module name `rdp4j`. Keyboard maps are
packaged in the JAR under `/keymaps`.
