package com.cloudcore.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the client-owned log file without replacing Minecraft's console/log4j config.
 * One rolling appender writes all {@code cloudcore} logs to
 * {@code logs/cloudcore-client.log}; rotation is 10 MiB x 5.
 */
final class ClientLogFile {
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudCoreClient.MOD_ID);
    private static final String APPENDER_NAME = "CloudCoreFile";
    private static final String FILE_NAME = "logs/cloudcore-client.log";
    private static final String FILE_PATTERN = "logs/cloudcore-client-%i.log";

    private ClientLogFile() {
    }

    static void install() {
        try {
            Files.createDirectories(Path.of("logs"));
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            if (configuration.getAppender(APPENDER_NAME) != null) {
                return;
            }
            PatternLayout layout = PatternLayout.newBuilder()
                    .withConfiguration(configuration)
                    .withPattern("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%logger{36}] %msg%n")
                    .build();
            RollingFileAppender appender = RollingFileAppender.newBuilder()
                    .setName(APPENDER_NAME)
                    .setConfiguration(configuration)
                    .withFileName(FILE_NAME)
                    .withFilePattern(FILE_PATTERN)
                    .withAppend(true)
                    .withCreateOnDemand(true)
                    .withPolicy(SizeBasedTriggeringPolicy.createPolicy("10 MB"))
                    .withStrategy(new DefaultRolloverStrategy.Builder()
                            .withMax("5")
                            .withMin("1")
                            .withConfig(configuration)
                            .build())
                    .setLayout(layout)
                    .build();
            appender.start();
            configuration.addAppender(appender);
            LoggerConfig loggerConfig = configuration.getLoggers().get(CloudCoreClient.MOD_ID);
            if (loggerConfig == null) {
                // Do not mutate the root logger: getLoggerConfig() falls back to root and
                // would capture every Minecraft/Fabric log line.
                loggerConfig = new LoggerConfig(CloudCoreClient.MOD_ID, Level.DEBUG, true);
                configuration.addLogger(CloudCoreClient.MOD_ID, loggerConfig);
            }
            loggerConfig.addAppender(appender, Level.DEBUG, null);
            context.updateLoggers();
            LOGGER.info("Client log file active: {} (10 MiB x 5)", FILE_NAME);
        } catch (Exception exception) {
            LOGGER.warn("Could not install client log file; continuing with console", exception);
        }
    }
}
