package fredboat.main;

import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary;
import fredboat.agent.CarbonitexAgent;
import fredboat.agent.FredBoatAgent;
import fredboat.agent.StatsAgent;
import fredboat.agent.VoiceChannelCleanupAgent;
import fredboat.audio.player.AudioConnectionFacade;
import fredboat.audio.player.PlayerLimiter;
import fredboat.audio.player.PlayerRegistry;
import fredboat.audio.player.VideoSelectionCache;
import fredboat.commandmeta.CommandInitializer;
import fredboat.commandmeta.CommandRegistry;
import fredboat.config.SentryConfiguration;
import fredboat.config.property.ConfigPropertiesProvider;
import fredboat.feature.I18n;
import fredboat.feature.metrics.BotMetrics;
import fredboat.jda.GuildProvider;
import fredboat.jda.ShardProvider;
import fredboat.util.AppInfo;
import fredboat.util.GitRepoState;
import fredboat.util.TextUtils;
import fredboat.util.rest.Http;
import fredboat.util.rest.TrackSearcher;
import fredboat.util.rest.Weather;
import fredboat.util.rest.YoutubeAPI;
import io.prometheus.client.guava.cache.CacheMetricsCollector;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDAInfo;
import okhttp3.Credentials;
import okhttp3.Response;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.annotation.ComponentScan;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * The class responsible for launching FredBoat
 */
@SpringBootApplication
@EnableAutoConfiguration(exclude = { //we handle these ourselves
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
@ComponentScan(basePackages = {
        "fredboat",
})
public class Launcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Launcher.class);
    public static final long START_TIME = System.currentTimeMillis();
    private static BotController BC; //temporary hack access to the bot context
    private final ConfigPropertiesProvider configProvider;
    private final ExecutorService executor;
    private final CacheMetricsCollector cacheMetrics;
    private final PlayerRegistry playerRegistry;
    private final StatsAgent statsAgent;
    private final BotMetrics botMetrics;
    private final Weather weather;
    private final AudioConnectionFacade audioConnectionFacade;
    private final TrackSearcher trackSearcher;
    private final VideoSelectionCache videoSelectionCache;
    private final ShardProvider shardProvider;
    private final GuildProvider guildProvider;
    private final SentryConfiguration sentryConfiguration;
    private final PlayerLimiter playerLimiter;
    private final YoutubeAPI youtubeAPI;

    public static void main(String[] args) throws IllegalArgumentException {
        //just post the info to the console
        if (args.length > 0 &&
                (args[0].equalsIgnoreCase("-v")
                        || args[0].equalsIgnoreCase("--version")
                        || args[0].equalsIgnoreCase("-version"))) {
            System.out.println("Version flag detected. Printing version info, then exiting.");
            System.out.println(getVersionInfo());
            System.out.println("Version info printed, exiting.");
            return;
        }
        log.info(getVersionInfo());

        int javaVersionMajor = -1;
        try {
            javaVersionMajor = Runtime.version().major();
        } catch (Exception e) {
            log.error("Exception while checking if java 9", e);
        }
        if (javaVersionMajor != 9) {
            log.warn("\n\t\t __      ___   ___ _  _ ___ _  _  ___ \n" +
                    "\t\t \\ \\    / /_\\ | _ \\ \\| |_ _| \\| |/ __|\n" +
                    "\t\t  \\ \\/\\/ / _ \\|   / .` || || .` | (_ |\n" +
                    "\t\t   \\_/\\_/_/ \\_\\_|_\\_|\\_|___|_|\\_|\\___|\n" +
                    "\t\t                                      ");
            log.warn("FredBoat only officially supports Java 9. You are running Java {}", Runtime.version());
        }

        System.setProperty("spring.config.name", "fredboat");
        System.setProperty("spring.http.converters.preferred-json-mapper", "gson");
        SpringApplication app = new SpringApplication(Launcher.class);
        app.addListeners(event -> {
            if (event instanceof ApplicationFailedEvent) {
                ApplicationFailedEvent failed = (ApplicationFailedEvent) event;
                log.error("Application failed", failed.getException());
            }
        });
        app.run(args);
    }

    public static BotController getBotController() {
        return BC;
    }

    public Launcher(BotController botController, ConfigPropertiesProvider configProvider, ExecutorService executor,
                    CacheMetricsCollector cacheMetrics, PlayerRegistry playerRegistry,
                    StatsAgent statsAgent, BotMetrics botMetrics, Weather weather,
                    AudioConnectionFacade audioConnectionFacade, TrackSearcher trackSearcher,
                    VideoSelectionCache videoSelectionCache, ShardProvider shardProvider, GuildProvider guildProvider,
                    SentryConfiguration sentryConfiguration, PlayerLimiter playerLimiter, YoutubeAPI youtubeAPI) {
        Launcher.BC = botController;
        this.configProvider = configProvider;
        this.executor = executor;
        this.cacheMetrics = cacheMetrics;
        this.playerRegistry = playerRegistry;
        this.statsAgent = statsAgent;
        this.botMetrics = botMetrics;
        this.weather = weather;
        this.audioConnectionFacade = audioConnectionFacade;
        this.trackSearcher = trackSearcher;
        this.videoSelectionCache = videoSelectionCache;
        this.shardProvider = shardProvider;
        this.guildProvider = guildProvider;
        this.sentryConfiguration = sentryConfiguration;
        this.playerLimiter = playerLimiter;
        this.youtubeAPI = youtubeAPI;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {

        I18n.start();

        //Commands
        CommandInitializer.initCommands(cacheMetrics, weather, trackSearcher, videoSelectionCache, sentryConfiguration,
                playerLimiter, youtubeAPI);
        log.info("Loaded commands, registry size is " + CommandRegistry.getTotalSize());

        if (!configProvider.getAppConfig().isPatronDistribution()) {
            log.info("Starting VoiceChannelCleanupAgent.");
            FredBoatAgent.start(new VoiceChannelCleanupAgent(playerRegistry, guildProvider, audioConnectionFacade));
        } else {
            log.info("Skipped setting up the VoiceChannelCleanupAgent, " +
                    "either running Patron distro or overridden by temp config");
        }

        //Check MAL creds
        executor.submit(this::hasValidMALLogin);

        //Check imgur creds
        executor.submit(this::hasValidImgurCredentials);

        FredBoatAgent.start(statsAgent);

        String carbonKey = configProvider.getCredentials().getCarbonKey();
        if (configProvider.getAppConfig().isMusicDistribution() && !carbonKey.isEmpty()) {
            FredBoatAgent.start(new CarbonitexAgent(configProvider.getCredentials(), botMetrics, shardProvider));
        }

        //force a count once all shards are up, this speeds up the stats availability on small boats
        while (areThereNotConnectedShards()) {
            Thread.sleep(1000);
        }
        statsAgent.run();
    }

    // ################################################################################
    // ##                     Login / credential tests
    // ################################################################################

    private boolean hasValidMALLogin() {
        String malUser = configProvider.getCredentials().getMalUser();
        String malPassWord = configProvider.getCredentials().getMalPassword();
        if (malUser.isEmpty() || malPassWord.isEmpty()) {
            log.info("MAL credentials not found. MAL related commands will not be available.");
            return false;
        }

        Http.SimpleRequest request = BotController.Companion.getHTTP().get("https://myanimelist.net/api/account/verify_credentials.xml")
                .auth(Credentials.basic(malUser, malPassWord));

        try (Response response = request.execute()) {
            if (response.isSuccessful()) {
                log.info("MAL login successful");
                return true;
            } else {
                //noinspection ConstantConditions
                log.warn("MAL login failed with {}\n{}", response.toString(), response.body().string());
            }
        } catch (IOException e) {
            log.warn("MAL login failed, it seems to be down.", e);
        }
        return false;
    }

    private boolean hasValidImgurCredentials() {
        String imgurClientId = configProvider.getCredentials().getImgurClientId();
        if (imgurClientId.isEmpty()) {
            log.info("Imgur credentials not found. Commands relying on Imgur will not work properly.");
            return false;
        }
        Http.SimpleRequest request = BotController.Companion.getHTTP().get("https://api.imgur.com/3/credits")
                .auth("Client-ID " + imgurClientId);
        try (Response response = request.execute()) {
            //noinspection ConstantConditions
            String content = response.body().string();
            if (response.isSuccessful()) {
                JSONObject data = new JSONObject(content).getJSONObject("data");
                //https://api.imgur.com/#limits
                //at the time of the introduction of this code imgur offers daily 12500 and hourly 500 GET requests for open source software
                //hitting the daily limit 5 times in a month will blacklist the app for the rest of the month
                //we use 3 requests per hour (and per restart of the bot), so there should be no problems with imgur's rate limit
                int hourlyLimit = data.getInt("UserLimit");
                int hourlyLeft = data.getInt("UserRemaining");
                long seconds = data.getLong("UserReset") - (System.currentTimeMillis() / 1000);
                String timeTillReset = String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, (seconds % 60));
                int dailyLimit = data.getInt("ClientLimit");
                int dailyLeft = data.getInt("ClientRemaining");
                log.info("Imgur credentials are valid. " + hourlyLeft + "/" + hourlyLimit +
                        " requests remaining this hour, resetting in " + timeTillReset + ", " +
                        dailyLeft + "/" + dailyLimit + " requests remaining today.");
                return true;
            } else {
                log.warn("Imgur login failed with {}\n{}", response.toString(), content);
            }
        } catch (IOException e) {
            log.warn("Imgur login failed, it seems to be down.", e);
        }
        return false;
    }

    //returns true if all registered shards are reporting back as CONNECTED, false otherwise
    private boolean areThereNotConnectedShards() {
        return shardProvider.streamShards()
                .anyMatch(shard -> shard.getStatus() != JDA.Status.CONNECTED);
    }

    private static String getVersionInfo() {
        return "\n\n" +
                "  ______            _ ____              _   \n" +
                " |  ____|          | |  _ \\            | |  \n" +
                " | |__ _ __ ___  __| | |_) | ___   __ _| |_ \n" +
                " |  __| '__/ _ \\/ _` |  _ < / _ \\ / _` | __|\n" +
                " | |  | | |  __/ (_| | |_) | (_) | (_| | |_ \n" +
                " |_|  |_|  \\___|\\__,_|____/ \\___/ \\__,_|\\__|\n\n"

                + "\n\tVersion:       " + AppInfo.getAppInfo().VERSION
                + "\n\tBuild:         " + AppInfo.getAppInfo().BUILD_NUMBER
                + "\n\tCommit:        " + GitRepoState.getGitRepositoryState().commitIdAbbrev + " (" + GitRepoState.getGitRepositoryState().branch + ")"
                + "\n\tCommit time:   " + TextUtils.asTimeInCentralEurope(GitRepoState.getGitRepositoryState().commitTime * 1000)
                + "\n\tJVM:           " + System.getProperty("java.version")
                + "\n\tJDA:           " + JDAInfo.VERSION
                + "\n\tLavaplayer     " + PlayerLibrary.VERSION
                + "\n";
    }
}
