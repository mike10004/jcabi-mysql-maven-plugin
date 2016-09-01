/**
 * Copyright (c) 2012-2015, jcabi.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the jcabi.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jcabi.mysql.maven.plugin;

import com.jcabi.aspects.Loggable;
import com.jcabi.aspects.Tv;
import com.jcabi.log.Logger;
import com.jcabi.log.VerboseProcess;
import com.jcabi.log.VerboseRunnable;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.validation.constraints.NotNull;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.CharEncoding;
import org.apache.commons.lang3.StringUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import lombok.EqualsAndHashCode;
import lombok.ToString;


/**
 * Running instances of MySQL.
 *
 * <p>The class is thread-safe.
 * @author Yegor Bugayenko (yegor@tpc2.com)
 * @version $Id$
 * @checkstyle ClassDataAbstractionCoupling (500 lines)
 * @checkstyle MultipleStringLiterals (500 lines)
 * @since 0.1
 */
@ToString
@EqualsAndHashCode(of = "processes")
@Loggable(Loggable.INFO)
@SuppressWarnings({ "PMD.DoNotUseThreads", "PMD.TooManyMethods" })
public final class Instances {

    /**
     * Directory of the actual database relative to the target.
     */
    private static final String DATA_SUB_DIR = "data";

    /**
     * No defaults.
     */
    private static final String NO_DEFAULTS = "--no-defaults";

    /**
     * Default retry count.
     */
    private static final int RETRY_COUNT = 5;

    /**
     * Default user.
     */
    private static final String DEFAULT_USER = "root";

    /**
     * Default password.
     */
    private static final String DEFAULT_PASSWORD = "root";

    /**
     * Default host.
     */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * Running processes.
     */
    private final transient ConcurrentMap<Integer, Process> processes =
        new ConcurrentHashMap<Integer, Process>(0);

    /**
     * If true, always create a new database. If false, check if there is an
     * existing database at the target location and try to use that if
     * possible, otherwise create a new one anyway.
     */
    private transient boolean clean = true;

    /**
     * Start a new one at this port.
     * @param config Instance configuration
     * @param dist Path to MySQL distribution
     * @param target Where to keep temp data
     * @param deldir If existing DB should be deleted
     * @param socket Alternative socket location for mysql (may be null)
     * @throws IOException If fails to start
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    public void start(@NotNull final Config config, @NotNull final File dist,
        @NotNull final File target, final boolean deldir, final File socket)
        throws IOException {
        this.setClean(target, deldir);
        synchronized (this.processes) {
            if (this.processes.containsKey(config.port())) {
                throw new IllegalArgumentException(
                    String.format("port %d is already busy", config.port())
                );
            }
            final Process proc = this.process(config, dist, target, socket);
            this.processes.put(config.port(), proc);
            Runtime.getRuntime().addShutdownHook(
                new Thread(
                    new Runnable() {
                        @Override
                        public void run() {
                            Instances.this.stop(config.port());
                        }
                    }
                )
            );
        }
    }

    /**
     * Stop a running one at this port.
     * @param port The port to stop at
     */
    public void stop(final int port) {
        synchronized (this.processes) {
            final Process proc = this.processes.remove(port);
            if (proc != null) {
                proc.destroy();
            }
        }
    }

    /**
     * Returns if a clean database had to be created. Note that this must be
     * called after {@link Instances#start(Config, File, File, boolean)}.
     * @return If this is a clean database or could have been reused
     */
    public boolean reusedExistingDatabase() {
        return !this.clean;
    }

    private static List<String> buildMysqldArgs(@NotNull Config config, 
            @NotNull File dist, @NotNull File target, @NotNull File socket, 
            @NotNull File temp, @NotNull File data, boolean initialize) throws IOException {
        List<String> cmds = new ArrayList<String>();
        cmds.add(Instances.NO_DEFAULTS);
        if (initialize) {
            cmds.add("--initialize-insecure");
        }
        cmds.addAll(Arrays.asList(            
            String.format("--user=%s", System.getProperty("user.name")),
            "--general_log",
            "--console",
            "--innodb_buffer_pool_size=64M",
            "--innodb_log_file_size=64M",
            "--log_warnings",
            "--innodb_use_native_aio=0",
            String.format("--binlog-ignore-db=%s", config.dbname()),
            String.format("--basedir=%s", dist),
            String.format("--lc-messages-dir=%s", new File(dist, "share")),
            String.format("--datadir=%s", data),
            String.format("--tmpdir=%s", temp),
            String.format("--socket=%s", socket),
            String.format("--pid-file=%s", new File(target, "mysql.pid")),
            String.format("--port=%d", config.port())));
        return cmds;        
    }
    
    /**
     * Start a new process.
     * @param config Instance configuration
     * @param dist Path to MySQL distribution
     * @param target Where to keep temp data
     * @param socketfile Alternative socket location for mysql (may be null)
     * @return Process started
     * @throws IOException If fails to start
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    private Process process(@NotNull final Config config,
        final File dist, final File target, final File socketfile)
        throws IOException {
        final File temp = this.prepareFolders(target);
        final File socket;
        if (socketfile == null) {
            socket = new File(target, "mysql.sock");
        } else {
            socket = socketfile;
        }
        final File dataDir = new File(target, Instances.DATA_SUB_DIR);
        initializeDataDir(config, dist, target, dataDir, socket, temp);
        List<String> cmds = buildMysqldArgs(config, dist, target, socket, temp, dataDir, false);
        final ProcessBuilder builder = this.builder(
            dist,
            "bin/mysqld",
            cmds.toArray(new String[cmds.size()])
        ).redirectErrorStream(true);
        builder.environment().put("MYSQL_HOME", dist.getAbsolutePath());
        for (final String option : config.options()) {
            if (!StringUtils.isBlank(option)) {
                builder.command().add(String.format("--%s", option));
            }
        }
        final Process proc = builder.start();
        final Thread thread = new Thread(
            new VerboseRunnable(
                new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        new VerboseProcess(proc).stdoutQuietly();
                        return null;
                    }
                }
            )
        );
        thread.setDaemon(true);
        thread.start();
        this.waitFor(socket, config.port());
        if (this.clean) {
            this.configure(config, dist, socket);
        }
        return proc;
    }

    /**
     * Prepare the folder structure for the database if necessary.
     * @param target Location of the database
     * @return The location of the temp directory
     * @throws IOException If fails to create temp directory
     */
    private File prepareFolders(final File target) throws IOException {
        if (this.clean && target.exists()) {
            FileUtils.deleteDirectory(target);
            Logger.info(this, "deleted %s directory", target);
        }
        if (!target.exists() && target.mkdirs()) {
            Logger.info(this, "created %s directory", target);
        }
        final File temp = new File(target, "temp");
        if (!temp.exists() && !temp.mkdirs()) {
            throw new IllegalStateException(
                "Error during temporary folder creation"
            );
        }
        return temp;
    }
    
    /**
     * Enumeration representing strategies for database server instance 
     * initialization. The {@code mysql_install_db} script/program is 
     * used for server instance, but exactly where to find that 
     * script/program and what arguments to use differs depending on
     * the version of MySQL or MariaDB.
     */
    static enum InitStrategy {
        USE_SCRIPTS_MYSQL_INSTALL_DB,
        USE_BIN_MYSQL_INSTALL_DB,
        USE_MYSQLD_INITIALIZE_OPTION
    }
    
    /**
     * Decide on the initialization strategy. This method detects whether 
     * {@code mysql_install_db} is a script in the {@code scripts} 
     * directory, as it was prior to MySQL 5.7.6, or a binary executable 
     * in the {@code bin} directory.
     * @param dist the distribution directory
     * @return the initialization strategy
     */
    static InitStrategy decideInitStrategy(File dist) {
        String[] suffixes = { "", ".exe", ".pl" };
        File binParent = new File(dist, "bin"), scriptsParent = new File(dist, "scripts");
        for (String suffix : suffixes) {
            String filename = "mysql_install_db" + suffix;
            if (new File(scriptsParent, filename).isFile()) {
                return InitStrategy.USE_SCRIPTS_MYSQL_INSTALL_DB;
            }
            if (new File(binParent, filename).isFile()) {
                return InitStrategy.USE_BIN_MYSQL_INSTALL_DB;
            }
        }
        return InitStrategy.USE_MYSQLD_INITIALIZE_OPTION;
    }
    
    /**
     * Prepare and return data directory.
     * @param dist Path to MySQL distribution
     * @param target Where to create it
     * @return Directory created
     * @throws IOException If fails
     */
    private void initializeDataDir(final Config config, final File dist, final File target, final File dataDir, final File socket, final File temp) throws IOException {
        if (!dataDir.exists()) {
            final File cnf = new File(
                new File(dist, "share"),
                "my-default.cnf"
            );
            FileUtils.writeStringToFile(
                cnf,
                "[mysql]\n# no defaults..."
            );
            InitStrategy initStrategy = decideInitStrategy(dist);
            Logger.debug(this, "using initialization strategy: %s", initStrategy);            
            if (InitStrategy.USE_SCRIPTS_MYSQL_INSTALL_DB == initStrategy) {
                new MoreVerboseProcess(
                    this.builder(
                        dist,
                        "scripts/mysql_install_db",
                        String.format("--defaults-file=%s", cnf),
                        "--force",
                        "--innodb_use_native_aio=0",
                        String.format("--datadir=%s", dataDir),
                        String.format("--basedir=%s", dist)
                    )
                ).output();
            } else if (InitStrategy.USE_BIN_MYSQL_INSTALL_DB == initStrategy) {
                new MoreVerboseProcess(
                    this.builder(
                        dist,
                        "bin/mysql_install_db",
                        String.format("--defaults-file=%s", cnf),
                        String.format("--datadir=%s", dataDir),
                        "--insecure",
                        "--verbose",
                        String.format("--basedir=%s", dist)                    
                    )
                ).output();
            } else if (InitStrategy.USE_MYSQLD_INITIALIZE_OPTION == initStrategy) {
                dataDir.mkdirs();
                if (!dataDir.isDirectory()) {
                    throw new IOException("failed to create directory " + dataDir);
                }
                List<String> args = buildMysqldArgs(config, dist, target, socket, temp, dataDir, true);
                new MoreVerboseProcess(
                    this.builder(
                        dist,
                        "bin/mysqld",
                        args.toArray(new String[args.size()])
                    )
                ).output();
            } else {
                throw new IllegalArgumentException("initialization strategy not recognized: " + initStrategy);
            }
        }
    }

    /**
     * Wait for this file to become available.
     * @param socket The file to wait for
     * @param port Port to wait for
     * @return The same socket, but ready for usage
     * @throws IOException If fails
     */
    private File waitFor(final File socket, final int port) throws IOException {
        final long start = System.currentTimeMillis();
        long age = 0L;
        while (true) {
            if (socket.exists()) {
                Logger.info(
                    this,
                    "socket %s is available after %[ms]s of waiting",
                    socket, age
                );
                break;
            }
            if (SocketHelper.isOpen(port)) {
                Logger.info(
                    this,
                    "port %s is available after %[ms]s of waiting",
                    port, age
                );
                break;
            }
            try {
                TimeUnit.SECONDS.sleep(1L);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            age = System.currentTimeMillis() - start;
            if (age > TimeUnit.MINUTES.toMillis((long) Tv.FIVE)) {
                throw new IOException(
                    Logger.format(
                        "socket %s is not available after %[ms]s of waiting",
                        socket, age
                    )
                );
            }
        }
        return socket;
    }

    /**
     * Configure the running MySQL server.
     * @param config Instance configuration
     * @param dist Directory with MySQL distribution
     * @param socket Socket of it
     * @throws IOException If fails
     */
    private void configure(@NotNull final Config config,
        final File dist, final File socket)
        throws IOException {
        new VerboseProcess(
            this.builder(
                dist,
                "bin/mysqladmin",
                Instances.NO_DEFAULTS,
                String.format("--wait=%d", Instances.RETRY_COUNT),
                String.format("--port=%d", config.port()),
                String.format("--user=%s", Instances.DEFAULT_USER),
                String.format("--socket=%s", socket),
                String.format("--host=%s", Instances.DEFAULT_HOST),
                "password",
                Instances.DEFAULT_PASSWORD
            )
        ).stdout();
        final Process process =
            this.builder(
                dist,
                "bin/mysql",
                String.format("--port=%d", config.port()),
                String.format("--user=%s", Instances.DEFAULT_USER),
                String.format("--password=%s", Instances.DEFAULT_PASSWORD),
                String.format("--socket=%s", socket)
            ).start();
        final PrintWriter writer = new PrintWriter(
            new OutputStreamWriter(
                process.getOutputStream(), CharEncoding.UTF_8
            )
        );
        writer.print("CREATE DATABASE ");
        writer.print(config.dbname());
        writer.println(";");
        if (!Instances.DEFAULT_USER.equals(config.user())) {
            writer.println(
                String.format(
                    "CREATE USER '%s'@'%s' IDENTIFIED BY '%s';",
                    config.user(),
                    Instances.DEFAULT_HOST,
                    config.password()
                )
            );
            writer.println(
                String.format(
                    "GRANT ALL ON %s.* TO '%s'@'%s';",
                    config.dbname(),
                    config.user(),
                    Instances.DEFAULT_HOST
                )
            );
        }
        writer.close();
        new VerboseProcess(process).stdout();
    }

    /**
     * Make process builder with this commands.
     * @param dist Distribution directory
     * @param name Name of the cmd to run
     * @param cmds Commands
     * @return Process builder
     */
    private ProcessBuilder builder(final File dist, final String name,
        final String... cmds) {
        String label = name;
        final Collection<String> commands = new LinkedList<String>();
        final File exec = new File(dist, label);
        if (exec.exists()) {
            try {
                exec.setExecutable(true);
            } catch (final SecurityException sex) {
                throw new IllegalStateException(sex);
            }
        } else {
            label = String.format("%s.exe", name);
            if (!new File(dist, label).exists()) {
                label = String.format("%s.pl", name);
                commands.add("perl");
            }
        }
        commands.add(new File(dist, label).getAbsolutePath());
        commands.addAll(Arrays.asList(cmds));
        Logger.info(this, "$ %s", StringUtils.join(commands, " "));
        return new ProcessBuilder()
            .command(commands.toArray(new String[commands.size()]))
            .directory(dist);
    }

    /**
     * Will set the {@link Instances#clean} flag, indicating if the database
     * can be reused or if it should be deleted and recreated.
     * @param target Location of database
     * @param deldir Should database always be cleared
     */
    private void setClean(final File target, final boolean deldir) {
        if (new File(target, Instances.DATA_SUB_DIR).exists() && !deldir) {
            Logger.info(this, "reuse existing database %s", target);
            this.clean = false;
        } else {
            this.clean = true;
        }
        Logger.info(this, "reuse existing database %s", !this.clean);
    }

}

/**
 * Copyright (c) 2012-2014, jcabi.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the jcabi.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * Utility class for getting {@code stdout} from a running process
 * and logging it through SLF4J.
 *
 * <p>For example:
 *
 * <pre> String name = new VerboseProcess(
 *   new ProcessBuilder("who", "am", "i")
 * ).stdout();</pre>
 *
 * <p>The class throws an exception if the process returns a non-zero exit
 * code.
 *
 * <p>The class is thread-safe.
 *
 * @author Yegor Bugayenko (yegor@tpc2.com)
 * @version $Id$
 * @since 0.5
 */
@ToString
@EqualsAndHashCode(of = "process")
final class MoreVerboseProcess implements Closeable {

    /**
     * Charset.
     */
    private static final String UTF_8 = "UTF-8";

    /**
     * The process we're working with.
     */
    private final transient Process process;

    /**
     * Log level for stdout.
     */
    private final transient Level olevel;

    /**
     * Log level for stderr.
     */
    private final transient Level elevel;

    /**
     * Public ctor.
     * @param prc The process to work with
     */
    public MoreVerboseProcess(final Process prc) {
        this(prc, Level.INFO, Level.WARNING);
    }

    /**
     * Public ctor (builder will be configured to redirect error input to
     * the {@code stdout} and will receive an empty {@code stdin}).
     * @param builder Process builder to work with
     */
    public MoreVerboseProcess(final ProcessBuilder builder) {
        this(MoreVerboseProcess.start(builder));
    }

    /**
     * Public ctor, with a given process and logging levels for {@code stdout}
     * and {@code stderr}.
     * @param prc Process to execute and monitor
     * @param stdout Log level for stdout
     * @param stderr Log level for stderr
     * @since 0.11
     */
    public MoreVerboseProcess(final Process prc, final Level stdout,
        final Level stderr) {
        if (prc == null) {
            throw new IllegalArgumentException("process can't be NULL");
        }
        if (stdout == null) {
            throw new IllegalArgumentException("stdout LEVEL can't be NULL");
        }
        if (stderr == null) {
            throw new IllegalArgumentException("stderr LEVEL can't be NULL");
        }
        this.process = prc;
        this.olevel = stdout;
        this.elevel = stderr;
    }

    /**
     * Public ctor, with a given process and logging levels for {@code stdout}
     * and {@code stderr}.
     * @param bdr Process builder to execute and monitor
     * @param stdout Log level for stdout
     * @param stderr Log level for stderr
     * @since 0.12
     */
    public MoreVerboseProcess(final ProcessBuilder bdr, final Level stdout,
        final Level stderr) {
        this(MoreVerboseProcess.start(bdr), stdout, stderr);
    }

    /**
     * Get {@code stdout} from the process, after its finish (the method will
     * wait for the process and log its output).
     *
     * <p>The method will check process exit code, and if it won't be equal
     * to zero a runtime exception will be thrown. A non-zero exit code
     * usually is an indicator of problem. If you want to ignore this code,
     * use {@link #stdoutQuietly()} instead.
     *
     * @return Full {@code stdout} of the process
     */
    public ProcessOutput output() {
        return this.output(true);
    }

    /**
     * Get {@code stdout} from the process, after its finish (the method will
     * wait for the process and log its output).
     *
     * <p>This method ignores exit code of the process. Even if it is
     * not equal to zero (which usually is an indicator of an error), the
     * method will quietly return its output. The method is useful when
     * you're running a background process. You will kill it with
     * {@link Process#destroy()}, which usually will lead to a non-zero
     * exit code, which you want to ignore.
     *
     * @return Full {@code stdout} of the process
     * @since 0.10
     */
    public ProcessOutput outputQuietly() {
        return this.output(false);
    }

    // @todo #38:30min When VerboseProcess is closed, we should also shut down
    //  the monitor threads and prevent them trying to obtain the output from
    //  the process. It should be done before destroy() is called. See the
    //  following for more details: https://github.com/jcabi/jcabi-log/issues/38
    //  http://www.java2s.com/Code/Java/Threads/Thesafewaytostopathread.htm
    @Override
    public void close() {
        this.process.destroy();
    }

    /**
     * Start a process from the given builder.
     * @param builder Process builder to work with
     * @return Process started
     */
    private static Process start(final ProcessBuilder builder) {
        if (builder == null) {
            throw new IllegalArgumentException("builder can't be NULL");
        }
        try {
            final Process process = builder.start();
            process.getOutputStream().close();
            return process;
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Get standard output and check for non-zero exit code (if required).
     * @param check TRUE if we should check for non-zero exit code
     * @return Full {@code stdout} of the process
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    private ProcessOutput output(final boolean check) {
        final long start = System.currentTimeMillis();
        final ProcessOutput processOutput;
        try {
            processOutput = this.waitFor();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        final int code = this.process.exitValue();
        Logger.debug(
            this,
            "#stdout(): process %s completed (code=%d, stdout.size=%d, stderr.size=%d) in %[ms]s",
            this.process, code, processOutput.stdout.length(), processOutput.stderr.length(),
            System.currentTimeMillis() - start
        );
        if (check && code != 0) {
            throw new IllegalArgumentException(
                Logger.format("Non-zero exit code %d: stdout = %[text]s; stderr = %[text]s", code, processOutput.stdout, processOutput.stderr)
            );
        }
        return processOutput;
    }

    public static class ProcessOutput {
        
        public final String stdout;
        public final String stderr;

        public ProcessOutput(String stdout, String stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }
        
    }
    
    /**
     * Wait for the process to stop, logging its output in parallel.
     * @return Stdout produced by the process
     * @throws InterruptedException If interrupted in between
     */
    private ProcessOutput waitFor() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(2);
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Logger.debug(
            this,
            "#waitFor(): waiting for stdout of %s in %s...",
            this.process,
            MoreVerboseProcess.monitor(
                this.process.getInputStream(),
                done, stdout, this.olevel
            )
        );
        Logger.debug(
            this,
            "#waitFor(): waiting for stderr of %s in %s...",
            this.process,
            MoreVerboseProcess.monitor(
                this.process.getErrorStream(),
                done, stderr, this.elevel
            )
        );
        try {
            this.process.waitFor();
        } finally {
            Logger.debug(
                this, "#waitFor(): process finished: %s", this.process
            );
            if (!done.await(2L, TimeUnit.SECONDS)) {
                Logger.error(this, "#wait() failed");
            }
        }
        try {
            String stdoutStr = stdout.toString(MoreVerboseProcess.UTF_8);
            String stderrStr = stderr.toString(MoreVerboseProcess.UTF_8);
            return new ProcessOutput(stdoutStr, stderrStr);
        } catch (final UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Monitor this input input.
     * @param input Stream to monitor
     * @param done Count down latch to signal when done
     * @param output Buffer to write to
     * @param level Logging level
     * @return Thread which is monitoring
     * @checkstyle ParameterNumber (6 lines)
     */
    @SuppressWarnings("PMD.DoNotUseThreads")
    private static Thread monitor(final InputStream input,
        final CountDownLatch done,
        final OutputStream output, final Level level) {
        final Thread thread = new Thread(
            new VerboseRunnable(
                new MoreVerboseProcess.Monitor(input, done, output, level),
                false
            )
        );
        thread.setName("VerboseProcess");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Close quietly.
     * @param res Resource to close
     */
    private static void close(final Closeable res) {
        try {
            res.close();
        } catch (final IOException ex) {
            Logger.error(
                VerboseProcess.class,
                "failed to close resource: %[exception]s",
                ex
            );
        }
    }

    /**
     * Stream monitor.
     */
    private static final class Monitor implements Callable<Void> {
        /**
         * Stream to read.
         */
        private final transient InputStream input;
        /**
         * Latch to count down when done.
         */
        private final transient CountDownLatch done;
        /**
         * Buffer to save output.
         */
        private final transient OutputStream output;
        /**
         * Log level.
         */
        private final transient Level level;
        /**
         * Ctor.
         * @param inp Stream to monitor
         * @param latch Count down latch to signal when done
         * @param out Buffer to write to
         * @param lvl Logging level
         * @checkstyle ParameterNumber (5 lines)
         */
        Monitor(final InputStream inp, final CountDownLatch latch,
            final OutputStream out, final Level lvl) {
            this.input = inp;
            this.done = latch;
            this.output = out;
            this.level = lvl;
        }
        @Override
        public Void call() throws Exception {
            final BufferedReader reader = new BufferedReader(
                new InputStreamReader(this.input, MoreVerboseProcess.UTF_8)
            );
            try {
                final BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(this.output, MoreVerboseProcess.UTF_8)
                );
                try {
                    while (true) {
                        final String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        Logger.log(
                            this.level, VerboseProcess.class,
                            ">> %s", line
                        );
                        writer.write(line);
                        writer.newLine();
                    }
                } catch (final IOException ex) {
                    Logger.error(
                        VerboseProcess.class,
                        "Error reading from process stream: %[exception]s",
                        ex
                    );
                } finally {
                    MoreVerboseProcess.close(writer);
                    this.done.countDown();
                }
            } finally {
                MoreVerboseProcess.close(reader);
            }
            return null;
        }
    }

}
