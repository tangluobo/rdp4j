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
    <version>1.0.2</version>
</dependency>
```

## Publish to Maven Central

Configure the `mvn-center-repo` server credentials in Maven `settings.xml`,
then run:

```shell
mvn deploy
```

The build creates and signs the main, source, Javadoc, and POM artifacts. The
Central deployment is automatically published after validation. On Windows,
the project uses `.mvn/gpg.cmd` to locate GPG, including the copy bundled with
Git for Windows.

The frontend is selected explicitly through `RdpFrontend`. `SwingRdpFrontend`
retains the original Swing canvas and input path, while `FxRdpFrontend` uses a
JavaFX `WritableImage`/`ImageView` and sends JavaFX key and mouse events directly
as RDP input messages. `RdpPane` selects the pure JavaFX frontend and does not
embed Swing.

The JAR declares the automatic JPMS module name `rdp4j`. Keyboard maps are
packaged in the JAR under `/keymaps`.
