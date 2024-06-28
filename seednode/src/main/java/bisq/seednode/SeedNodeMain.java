/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.seednode;

import bisq.core.app.TorSetup;
import bisq.core.app.misc.ExecutableForAppWithP2p;
import bisq.core.dao.monitoring.DaoStateMonitoringService;
import bisq.core.dao.state.DaoStateSnapshotService;
import bisq.core.user.Cookie;
import bisq.core.user.CookieKey;
import bisq.core.user.User;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.P2PServiceListener;
import bisq.network.p2p.peers.PeerManager;
import bisq.network.p2p.seed.SeedNodeRepository;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Capabilities;
import bisq.common.app.Capability;
import bisq.common.app.DevEnv;
import bisq.common.app.Version;
import bisq.common.config.BaseCurrencyNetwork;
import bisq.common.config.Config;
import bisq.common.handlers.ResultHandler;

import com.google.inject.Key;
import com.google.inject.name.Names;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SeedNodeMain extends ExecutableForAppWithP2p {
    private static final long CHECK_CONNECTION_LOSS_SEC = 30;

    public static void main(String[] args) {
        new SeedNodeMain().execute(args);
    }

    private final SeedNode seedNode;
    private Timer checkConnectionLossTimer;

    public SeedNodeMain() {
        super("Bisq Seednode", "bisq-seednode", "bisq_seednode", Version.VERSION);

        seedNode = new SeedNode();
    }

    @Override
    protected void doExecute() {
        super.doExecute();

        checkMemory(config, this);
        keepRunning();
    }

    @Override
    protected void addCapabilities() {
        Capabilities.app.addAll(Capability.SEED_NODE);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UncaughtExceptionHandler implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void handleUncaughtException(Throwable throwable, boolean doShutDown) {
        if (throwable instanceof OutOfMemoryError || doShutDown) {
            log.error("We got an OutOfMemoryError and shut down");
            gracefulShutDown(() -> log.info("gracefulShutDown complete"));
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // We continue with a series of synchronous execution tasks
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void applyInjector() {
        super.applyInjector();

        seedNode.setInjector(injector);
    }

    @Override
    protected void startApplication() {
        super.startApplication();

        Cookie cookie = injector.getInstance(User.class).getCookie();
        cookie.getAsOptionalBoolean(CookieKey.CLEAN_TOR_DIR_AT_RESTART).ifPresent(cleanTorDirAtRestart -> {
            if (cleanTorDirAtRestart) {
                injector.getInstance(TorSetup.class).cleanupTorFiles(() ->
                                cookie.remove(CookieKey.CLEAN_TOR_DIR_AT_RESTART),
                        log::error);
            }
        });

        seedNode.startApplication();

        injector.getInstance(DaoStateMonitoringService.class).addListener(new DaoStateMonitoringService.Listener() {
            @Override
            public void onCheckpointFailed() {
                gracefulShutDown();
            }
        });

        injector.getInstance(DaoStateSnapshotService.class).setResyncDaoStateFromResourcesHandler(
                // We shut down with a deterministic delay per seed to avoid that all seeds shut down at the
                // same time in case of a reorg. We use 30 sec. as distance delay between the seeds to be on the
                // safe side. We have 12 seeds so that's 6 minutes.
                () -> UserThread.runAfter(this::gracefulShutDown, 1 + (getMyIndex() * 30L))
        );

        injector.getInstance(P2PService.class).addP2PServiceListener(new P2PServiceListener() {
            @Override
            public void onDataReceived() {
                // Do nothing
            }

            @Override
            public void onNoSeedNodeAvailable() {
                // Do nothing
            }

            @Override
            public void onNoPeersAvailable() {
                // Do nothing
            }

            @Override
            public void onUpdatedDataReceived() {
                // Do nothing
            }

            @Override
            public void onTorNodeReady() {
                // Do nothing
            }

            @Override
            public void onHiddenServicePublished() {
                boolean preventPeriodicShutdownAtSeedNode = injector.getInstance(Key.get(boolean.class,
                        Names.named(Config.PREVENT_PERIODIC_SHUTDOWN_AT_SEED_NODE)));
                if (!preventPeriodicShutdownAtSeedNode) {
                    startShutDownInterval();
                }
                UserThread.runAfter(() -> setupConnectionLossCheck(), 60);
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
                // Do nothing
            }

            @Override
            public void onRequestCustomBridges() {
                // Do nothing
            }
        });
    }

    @Override
    public void gracefulShutDown(ResultHandler resultHandler) {
        seedNode.shutDown();
        if (checkConnectionLossTimer != null) {
            checkConnectionLossTimer.stop();
        }
        super.gracefulShutDown(resultHandler);
    }

    @Override
    public void startShutDownInterval() {
        if (DevEnv.isDevMode() || injector.getInstance(Config.class).useLocalhostForP2P) {
            return;
        }

        int myIndex = getMyIndex();
        if (myIndex == -1) {
            super.startShutDownInterval();
            return;
        }

        // We interpret the value of myIndex as hour of day (0-23). That way we avoid the risk of a restart of
        // multiple nodes around the same time in case it would be not deterministic.

        // We wrap our periodic check in a delay of 2 hours to avoid that we get
        // triggered multiple times after a restart while being in the same hour. It can be that we miss our target
        // hour during that delay but that is not considered problematic, the seed would just restart a bit longer than
        // 24 hours.
        UserThread.runAfter(() -> {
            // We check every hour if we are in the target hour.
            UserThread.runPeriodically(() -> {
                int currentHour = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC")).getHour();

                // distribute evenly between 0-23
                int size = injector.getInstance(SeedNodeRepository.class).getSeedNodeAddresses().size();
                long target = Math.round(24d / size * myIndex) % 24;
                if (currentHour == target) {
                    log.warn("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                                    "Shut down node at hour {} (UTC time is {})" +
                                    "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n\n",
                            target,
                            ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC")).toString());
                    shutDown(this);
                }
            }, TimeUnit.MINUTES.toSeconds(10));
        }, TimeUnit.HOURS.toSeconds(2));
    }

    private int getMyIndex() {
        SeedNodeRepository seedNodeRepository = injector.getInstance(SeedNodeRepository.class);
        List<NodeAddress> seedNodeAddresses = new ArrayList<>(seedNodeRepository.getSeedNodeAddresses());
        seedNodeAddresses.sort(Comparator.comparing(NodeAddress::getFullAddress));

        NodeAddress myAddress = injector.getInstance(P2PService.class).getAddress();
        return seedNodeAddresses.indexOf(myAddress);
    }

    private void setupConnectionLossCheck() {
        // For dev testing (usually on BTC_REGTEST) we don't want to get the seed shut
        // down as it is normal that the seed is the only actively running node.
        if (Config.baseCurrencyNetwork() == BaseCurrencyNetwork.BTC_REGTEST) {
            return;
        }

        if (checkConnectionLossTimer != null) {
            return;
        }

        checkConnectionLossTimer = UserThread.runPeriodically(() -> {
            if (injector.getInstance(PeerManager.class).getNumAllConnectionsLostEvents() > 1) {
                // We set a flag to clear tor cache files at re-start. We cannot clear it now as Tor is used and
                // that can cause problems.
                injector.getInstance(User.class).getCookie().putAsBoolean(CookieKey.CLEAN_TOR_DIR_AT_RESTART, true);
                shutDown(this);
            }
        }, CHECK_CONNECTION_LOSS_SEC);
    }
}
