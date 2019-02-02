/*
 * Copyright 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.connector.elasticsearch.cli;

import com.couchbase.connector.VersionHelper;
import com.couchbase.connector.cluster.consul.DocumentKeys;
import com.couchbase.connector.config.es.ConnectorConfig;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import com.orbitz.consul.Consul;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import static com.couchbase.connector.elasticsearch.cli.ConsulCli.validateGroup;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static java.nio.charset.StandardCharsets.UTF_8;

@Command(name = "cbes-consul",
    description = "Couchbase Elasticsearch Connector commands for coordinated mode with Consul",
    mixinStandardHelpOptions = true,
    versionProvider = ConsulCli.class,
    subcommands = {
        ResumeCommand.class,
        ConfigureCommand.class,
        GetConfigCommand.class,
        PauseCommand.class,
        GroupsCommand.class,
        CheckpointClearCommand.class,
        CheckpointBackupCommand.class
    })
public class ConsulCli implements IVersionProvider {

  // Init Log4J. This must happen first before logger is declared.
  static {
    // Expect the launch script generated by Gradle to set this environment variable.
    final String appHome = System.getenv("APP_HOME");
    if (appHome == null) {
      // Use the default log4j2.xml file in the class path.
      System.err.println("WARNING: Environment variable 'APP_HOME' not set (launch script is responsible for this). " +
          "Using embedded logging config.");
    } else {
      System.setProperty("log4j.configurationFile", appHome + "/config/log4j2.xml");
    }
  }

  public static void validateGroup(String group) {
    final Consul consul = Consul.builder().build();
    final DocumentKeys keys = new DocumentKeys(consul.keyValueClient(), group);

    Collection<String> configuredGroups = keys.configuredGroups();

    if (!configuredGroups.contains(group)) {
      System.err.println("There is no configured group called '" + group + "'.");
      System.err.println("Configure the group first using the 'configure' command, or target an existing configured group.");
      if (configuredGroups.isEmpty()) {
        System.err.println("  (there are no existing groups)");
      } else {
        configuredGroups.forEach(groupName -> System.err.println("  " + groupName));
      }
      System.exit(1);
    }
  }

  @Override
  public String[] getVersion() throws Exception {
    return new String[]{
        "Couchbase Elasticsearch Connector " + VersionHelper.getVersionString(),
        "Java " + System.getProperty("java.version") + " " + System.getProperty("java.vendor") + " " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"),
        System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")
    };
  }

  public static void main(String[] args) {
    CommandLine cmd = new CommandLine(new ConsulCli());

    //cmd.getHelpSectionMap().put(SECTION_KEY_COMMAND_LIST, new MyCommandListRenderer());

    try {
      List<Object> result = cmd.parseWithHandler(new CommandLine.RunLast(), args);
    } catch (CommandLine.ExecutionException e) {
      Object command = e.getCommandLine().getCommand();
      if (!(command instanceof Runnable) && !(command instanceof Callable)) {
        e.getCommandLine().usage(System.err);
        System.exit(1);
        return;
      }
      throw e;
    }


//    Object results = cmd.parseWithHandler(parseResult -> {
//      List<CommandLine> list = parseResult.asCommandLineList();
//      if (parseResult.isUsageHelpRequested()) {
//
//        cmd.usage(System.err);
//        return null;
//      }
//      if (parseResult.isVersionHelpRequested()) {
//        cmd.printVersionHelp(System.err);
//        return null;
//      }
//
//      System.out.println("has subcommand? " + parseResult.hasSubcommand());
//
//      System.out.println("subcommand subcommand? " + parseResult.subcommand());
//      return null;
//    }, args);
  }

//  @Override
//  public void run() {
//    System.out.println("Running this I guess?");
//  }
}

//@Command(name = "checkpoint",
//    description = "Subcommands let you back up, restore, clear, or catch-up the replication checkpoint.",
//    mixinStandardHelpOptions = true,
//    versionProvider = VersionProvider.class,
//    subcommands = {Clear.class, Backup.class})
//class CheckpointCommand {
//}

@Command(name = "checkpoint-clear",
    description = "Clears the replication checkpoint for the specified group, causing the connector to replicate from the beginning.")
class CheckpointClearCommand implements Runnable {

  @Option(names = {"-g", "--group"}, required = true,
      description = "The name of the connector group to operate on.")
  private String group;

//  @CommandLine.Parameters(index = "0", description = "The name of the connector group to operate on.")
//  private String group;

  @Override
  public void run() {

    validateGroup(group);
    final Consul consul = Consul.builder().build();
    final String configKey = new DocumentKeys(consul.keyValueClient(), group).config();

    final DocumentKeys documentKeys = new DocumentKeys(consul.keyValueClient(), group);

    final String configString = consul.keyValueClient().getValueAsString(configKey, UTF_8).orElse(null);
    if (Strings.isNullOrEmpty(configString)) {
      System.err.println("Failed to clear checkpoint. Connector configuration document does not exist, or is empty.");
      System.exit(1);
    }

    final ConnectorConfig config = ConnectorConfig.from(configString);
    try {
      System.out.println("Pausing connector prior to clearing checkpoints...");
      final boolean wasPausedAlready = documentKeys.pause();

      System.out.println("Clearing checkpoints...");
      CheckpointClear.clear(config);
      if (!wasPausedAlready) {
        System.out.println("Resuming connector...");
        documentKeys.resume();
      }

      System.out.println("Checkpoint cleared.");

    } catch (Exception e) {
      throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }
}

@Command(name = "groups",
    description = "Prints the name of each configured connector group.")
class GroupsCommand implements Runnable {
  @Override
  public void run() {
    final Consul consul = Consul.builder().build();
    new DocumentKeys(consul.keyValueClient(), "").configuredGroups().forEach(System.out::println);
  }
}


@Command(name = "checkpoint-backup",
    description = "Saves the replication checkpoint to a file on the local filesystem.")
class CheckpointBackupCommand implements Runnable {

  @Option(names = {"-o", "--output"}, paramLabel = "<checkpoint.json>", required = true,
      description = "Checkpoint file to create. Tip: On Unix-like systems," +
          " include a timestamp like: %n    checkpoint-$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ).json ")
  private File output;

  @Override
  public void run() {
    System.out.println("Running the backup command, file = " + output.getAbsolutePath());
  }
}

@Command(name = "resume",
    description = "Resume the connector if it is paused.")
class ResumeCommand implements Runnable {

  @Option(names = {"-g", "--group"}, required = true,
      description = "The name of the connector group to operate on.")
  private String group;

  @Override
  public void run() {
    try {
      validateGroup(group);
      System.out.println("Attempting to resume connector group: " + group);

      final Consul consul = Consul.builder().build();
      final DocumentKeys keys = new DocumentKeys(consul.keyValueClient(), group);

      keys.resume();
      System.out.println("Connector group '" + group + "' is now resumed.");

    } catch (Exception e) {
      throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }
}


@Command(name = "configure",
    description = "Define a new connector group by uploading a configuration file." +
        " The name of the group is determined by the 'group' property in the config file.")
class ConfigureCommand implements Runnable {

  @Option(names = {"-i", "--input"}, paramLabel = "<config.toml>", required = true,
      description = "Configuration file to upload.")
  private File input;

  @Override
  public void run() {
    try {
      final Consul consul = Consul.builder().build();
      final String configString = Files.asCharSource(input, UTF_8).read();
      final ConnectorConfig parsed = ConnectorConfig.from(configString);
      final String group = parsed.group().name();
      System.out.println("Updating config for connector group '" + group + "'...");

      final DocumentKeys keys = new DocumentKeys(consul.keyValueClient(), group);
      boolean success = consul.keyValueClient().putValue(keys.config(), configString, UTF_8);
      if (!success) {
        throw new IOException("Failed to write config document to Consul.");
      }

      System.out.println("Configuration updated for connector group '" + group + "'.");

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

@Command(name = "get-config",
    description = "Retrieve the configuration for a connector group and save it to a file on the local filesystem.")
class GetConfigCommand implements Runnable {

  @Option(names = {"-o", "--output"}, paramLabel = "<config.toml>", required = true,
      description = "File to create.")
  private File output;

  @Option(names = {"-g", "--group"}, required = true,
      description = "The name of the connector group to operate on.")
  private String group;


  @Override
  public void run() {
    try {
      validateGroup(group);

      final Consul consul = Consul.builder().build();
      final DocumentKeys keys = new DocumentKeys(consul.keyValueClient(), group);

      String config = consul.keyValueClient().getValueAsString(keys.config(), UTF_8)
          .orElseThrow(() -> new IOException("missing config document in consul"));

      CheckpointBackup.atomicWrite(output, file -> {
        Files.write(config.getBytes(UTF_8), file);
      });

      System.out.println("Configuration for connector group '" + group + "' written to file " + output.getAbsolutePath());

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

@Command(name = "pause",
    description = "Pauses the connector.")
class PauseCommand implements Runnable {

  @Option(names = {"-g", "--group"}, required = true,
      description = "The name of the connector group to operate on.")
  private String group;

  @Override
  public void run() {
    validateGroup(group);
    System.out.println("Attempting to pause connector group: " + group);

    final Consul consul = Consul.builder().build();
    final DocumentKeys keys = new DocumentKeys(consul.keyValueClient(), group);

    try {
      keys.pause();
      System.out.println("Connector group '" + group + "' is now paused.");
    } catch (TimeoutException e) {
      System.err.println("Pause failed; timed out waiting for cluster to quiesce.");
      System.exit(1);
    } catch (Exception e) {
      throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }
}


class MyCommandListRenderer implements CommandLine.IHelpSectionRenderer {
  //@Override
  public String render(CommandLine.Help help) {
    CommandLine.Model.CommandSpec spec = help.commandSpec();
    if (spec.subcommands().isEmpty()) {
      return "";
    }

    // prepare layout: two columns
    // the left column overflows, the right column wraps if text is too long
    CommandLine.Help.TextTable textTable = CommandLine.Help.TextTable.forColumns(help.ansi(),
        new CommandLine.Help.Column(15, 2, CommandLine.Help.Column.Overflow.SPAN),
        new CommandLine.Help.Column(spec.usageMessage().width() - 15, 2, CommandLine.Help.Column.Overflow.WRAP));

    for (CommandLine subcommand : spec.subcommands().values()) {
      addHierarchy(subcommand, textTable, "");
    }
    return textTable.toString();
  }

  private void addHierarchy(CommandLine cmd, CommandLine.Help.TextTable textTable, String indent) {
    // create comma-separated list of command name and aliases
    String names = cmd.getCommandSpec().names().toString();
    names = names.substring(1, names.length() - 1); // remove leading '[' and trailing ']'

    // command description is taken from header or description
    String description = description(cmd.getCommandSpec().usageMessage());

    // add a line for this command to the layout
    textTable.addRowValues(indent + names, description);

    // add its subcommands (if any)
    for (CommandLine sub : cmd.getSubcommands().values()) {
      addHierarchy(sub, textTable, indent + "   ");
    }
  }

  private String description(CommandLine.Model.UsageMessageSpec usageMessage) {
    if (usageMessage.header().length > 0) {
      return usageMessage.header()[0];
    }
    if (usageMessage.description().length > 0) {
      return usageMessage.description()[0];
    }
    return "";
  }
}
