package cc.co.evenprime.bukkit.nocheat.checks.moving;

import org.bukkit.entity.Player;

import cc.co.evenprime.bukkit.nocheat.NoCheat;
import cc.co.evenprime.bukkit.nocheat.config.cache.ConfigurationCache;
import cc.co.evenprime.bukkit.nocheat.data.BaseData;
import cc.co.evenprime.bukkit.nocheat.data.MovingData;
import cc.co.evenprime.bukkit.nocheat.data.PreciseLocation;

/**
 * The morePacketsCheck (previously called SpeedhackCheck) will try to identify
 * players that send more than the usual amount of move-packets to the server to
 * be able to move faster than normal, without getting caught by the other
 * checks (flying/running).
 * 
 * It monitors the number of packets sent to the server within 1 second and
 * compares it to the "legal" number of packets for that timeframe (22).
 * 
 */
public class MorePacketsCheck {

    private final static int packetsPerTimeframe = 22;
    private final static int bufferLimit         = 30;
    private final NoCheat    plugin;

    public MorePacketsCheck(NoCheat plugin) {

        this.plugin = plugin;
    }

    /**
     * Ok, lets write down the internal logic, to not forget what I wanted to
     * do when I started it:
     * 
     * A second on the server has 20 ticks
     * A second on the client has 20 ticks
     * A unmodified client with no lag will send 1 packet per tick = 20 packets
     * Ideally checking for 20 packets/second on the server would work
     * 
     * But a client may send 10 packets in second 1, and 30 packets in second 2
     * due to lag. This should still be allowed, therefore we give a "buffer".
     * If a player doesn't use all of his 20 move packets in one second, he may
     * carry over unused packets to the next (the buffer is limited in size for
     * obvious reasons).
     * 
     * But the server may not be able to process all packets in time, e.g.
     * because it is busy saving the world.
     * 
     * Well that sounded strange...
     * Admin: "Hey server, what is taking you so long?"
     * Server: "I'm saving the world!"
     * Admin: "o_O"
     * 
     * Contrary to client lag, serverside lag could be measured. So let's do
     * that. A task will be executed every 20 server ticks, storing the time it
     * took the server to process those ticks. If it's much more than 1 second,
     * the server was busy, and packets may have piled up during that time. So a
     * good idea would be to ignore the following second completely, as it will
     * be used to process the stacked-up packets, getting the server back in
     * sync with the timeline.
     * 
     * Server identified as being busy -> ignore the second that follows. If
     * that second the server is still busy -> ignore the second after that too.
     * 
     * What's with the second during which the server is busy? Can there be more
     * packets during that time? Sure. But should we care? No, this initial lag
     * can be mitigated by using the time it took to do the 20 ticks and factor
     * it with the limit for packets. Problem solved. The only real problem are
     * packets that stack up in one second to get processed in the next, which
     * is what the "ignoring" is for.
     * 
     * So the general course of action would be:
     * 
     * 1. Collect packets processed within 20 server ticks = packetCounter
     * 2. Measure time taken for those 20 server ticks = elapsedTime
     * 3. elapsedTime >> 1 second -> ignore next check
     * 4. limit = 22 x elapsedTime
     * 5. difference = limit - packetCounter
     * 6. buffer = buffer + difference; if(buffer > 20) buffer = 20;
     * 7. if(buffer < 0) -> violation of size "buffer".
     * 8. reset packetCounter, wait for next 20 ticks to pass by.
     * 
     */
    public PreciseLocation check(final Player player, final BaseData data, final ConfigurationCache cc) {

        PreciseLocation newToLocation = null;

        final MovingData moving = data.moving;

        moving.morePacketsCounter++;
        if(!moving.morePacketsSetbackPoint.isSet()) {
            moving.morePacketsSetbackPoint.set(moving.from);
        }

        int ingameSeconds = plugin.getIngameSeconds();
        // Is at least a second gone by and has the server at least processed 20
        // ticks since last time
        if(ingameSeconds != moving.lastElapsedIngameSeconds) {

            int limit = (int) ((packetsPerTimeframe * plugin.getIngameSecondDuration()) / 1000L);

            int difference = limit - moving.morePacketsCounter;

            moving.morePacketsBuffer += difference;
            if(moving.morePacketsBuffer > bufferLimit)
                moving.morePacketsBuffer = bufferLimit;
            // Are we over the 22 event limit for that time frame now? (limit
            // increases with time)

            int packetsAboveLimit = (int) -moving.morePacketsBuffer;

            if(moving.morePacketsBuffer < 0)
                moving.morePacketsBuffer = 0;

            // Should we react? Only if the check doesn't get skipped and we
            // went over the limit
            if(!plugin.skipCheck() && packetsAboveLimit > 0) {
                moving.morePacketsViolationLevel += packetsAboveLimit;

                // Packets above limit
                data.log.packets = moving.morePacketsCounter - limit;
                data.log.check = "moving/morepackets";

                final boolean cancel = plugin.execute(player, cc.moving.morePacketsActions, (int) moving.morePacketsViolationLevel, moving.history, cc);

                if(cancel)
                    newToLocation = moving.morePacketsSetbackPoint;
            }

            // No new setbackLocation was chosen
            if(newToLocation == null) {
                moving.morePacketsSetbackPoint.set(player.getLocation());
            }

            if(moving.morePacketsViolationLevel > 0)
                // Shrink the "over limit" value by 20 % every second
                moving.morePacketsViolationLevel *= 0.8;

            moving.morePacketsCounter = 0; // Count from zero again
            moving.lastElapsedIngameSeconds = ingameSeconds;
        }

        return newToLocation;
    }
}
