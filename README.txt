WITHER FORM — Fabric for Minecraft Java 26.3

This is the mod SOURCE project. GitHub Actions will compile the installable JAR.

BUILD:
1. Create/open your GitHub repository.
2. Upload the CONTENTS of this folder to the repository root. Include the hidden .github folder.
3. Commit the files.
4. Open Actions and select “Build Wither Form”.
5. Run the workflow (or let the push build start).
6. When it succeeds, open the run and download the “WitherForm-26.3” artifact.
7. Extract the downloaded artifact and put the regular witherform-1.0.0.jar in your Minecraft 26.3 Fabric mods folder. Do not use a -sources.jar.

Requires Fabric Loader 0.20.1 or newer and Fabric API for Minecraft 26.3.
Requires Java 25.

If the build fails, the final lines of the “Build mod” log are needed to fix the source/API mismatch. A source ZIP is not itself an installable mod JAR.
