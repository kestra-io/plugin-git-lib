# Kestra Plugin Devcontainer

This devcontainer provides a quick and easy setup for anyone using VSCode to get up and running quickly with plugin development for Kestra. It bootstraps a docker container for you to develop inside of without the need to manually setup the environment for developing plugins.

---

## INSTRUCTIONS

### Setup:

Once you have this repo cloned to your local system, you will need to install the VSCode extension [Remote Development](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.vscode-remote-extensionpack).

Then run the following command from the command palette:
`Dev Containers: Open Folder in Container...` and select your Kestra root folder.

This will then put you inside a docker container ready for development.

NOTE: you'll need to wait for the gradle build to finish and compile Java files but this process should happen automatically within VSCode.

---

### Development:

It is recommended to read the following plugin development guide so you can better understand how to get started with plugin development: https://kestra.io/docs/plugin-developer-guide.

This repository is a shared kernel library, not a standalone plugin: it registers no task or trigger, so there is no
Kestra instance to run it in directly. Build it with:

```bash
$ ./gradlew build
```

To test changes against a consuming plugin (`plugin-git` or `plugin-ee-git`) before publishing a release, publish it
to your local Maven repository and point the consuming plugin's dependency at that version:

```bash
$ ./gradlew publishToMavenLocal
```

For an end-to-end run against a live Kestra instance, follow the local-run instructions in
[`plugin-git`](https://github.com/kestra-io/plugin-git) or [`plugin-ee-git`](https://github.com/kestra-io/plugin-ee-git).

`Tests`:

```bash
$ ./gradlew check --parallel
```

---

### GIT

If you want to commit to GitHub, make sure to navigate to the `~/.ssh` folder and either create a new SSH key or override the existing `id_ed25519` file and paste an existing SSH key from your local machine into this file. You will then need to change the permissions of the file by running: `chmod 600 id_ed25519`. This will allow you to then push to GitHub.

---
