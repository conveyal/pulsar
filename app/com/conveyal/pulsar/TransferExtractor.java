package com.conveyal.pulsar;

import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.geotools.referencing.GeodeticCalculator;
import org.mapdb.Fun;
import org.mapdb.Fun.Tuple2;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.index.strtree.STRtree;

/**
 * Extract data from a GTFS feed about transfer performance.
 * @author mattwigway
 *
 */
public class TransferExtractor {
    private final static Logger LOG = Logger.getLogger(TransferExtractor.class.getName());
    
    /** The minimum transfer time */
    private static final int minTransferTime = 2 * 60;
    
    /** the maximum transfer time before it is considered not a transfer. 90 minutes of waiting is pretty ridiculous */
    private static final int maxTransferTime = 60 * 90;
    
    /** how fast can we walk, in m/s? This is set slightly less than in OTP because we are using as-the-crow-flies distance */
    private static final double walkSpeed = 1;
    
    public final GTFSFeed feed;
    
    private STRtree stopsIndex;
    
    /** Map from route direction to trip IDs */
    private Multimap<RouteDirection, Trip> tripIndex;
    
    /** Map from stop to route directions */
    private Multimap<Stop, RouteDirection> routesByStop;
    
    /**
     * Usage: feed.zip route_id {0|1} out.csv
     * @param args
     */
    public static void main (String... args) throws Exception {
        GTFSFeed feed = GTFSFeed.fromFile(args[0]);
        LOG.info("feed loaded");
        
        TransferExtractor t = new TransferExtractor(feed);        
        RouteDirection rd = new RouteDirection(feed.routes.get(args[1]), Direction.fromGtfs(Integer.parseInt(args[2])));
        
        LOG.info("finding transfers");        
        Transfer[] transfers = t.getTransfers(rd, 100);
        
        LOG.info("found transfers to " + transfers.length + " route directions");
        
        int i = 0;
        OutputStream os = new FileOutputStream(new File(args[3]));
        Writer outfile = new PrintWriter(os);
        
        outfile.write("route_id,direction_id,destination,at,min,percentile_25,median,percentile_75,max,count\n");
        
        for (Transfer xfer : transfers) {
            if (++i % 50 == 0)
                LOG.info("processed " + (i - 1) + " transfers");

            t.addDistributionToTransfer(xfer, 7 * 60 * 60, 9 * 60 * 60);
            
            if (xfer.median == Integer.MIN_VALUE)
                // no transfers to this route.
                continue;
          
            // TODO: quoting
            outfile.write(xfer.toRouteDirection.route.route_id + ",");
            outfile.write(xfer.toRouteDirection.direction.toGtfs() + ",");
            outfile.write("\"" + t.getName(xfer.toRouteDirection) + "\",");
            outfile.write("\"" + xfer.fromStop.stop_name + "\",");
            outfile.write(xfer.min + ",");
            outfile.write(xfer.pct25 + ",");
            outfile.write(xfer.median + ",");
            outfile.write(xfer.pct75 + ",");
            outfile.write(xfer.max + ",");
            outfile.write(xfer.n + "\n");
        }
        
        outfile.close();
        os.close();
        
        LOG.info("done");
    }
    
    /**
     * Create a new transfer extractor for the given GTFS file.
     */
    public TransferExtractor(File feed) {
        this(GTFSFeed.fromFile(feed.getAbsolutePath()));
    }
    
    /**
     * Create a new transfer extractor for the given GTFS feed. Assumes the feed has already been loaded.
     * @param feed
     */
    public TransferExtractor(GTFSFeed feed) {
        this.feed = feed;
        
        LOG.info("Spatially indexing stops");
        indexStops();
        LOG.info("Indexing trips");
        indexTrips();
        LOG.info("Indexing routes");
        indexRouteStops();
        LOG.info("Done indexing");
    }
    
    /**
     * Index stops geographically, so that we can quickly find which routes cross other routes.
     */
    private void indexStops () {
        // create a spatial index for stops
        stopsIndex = new STRtree(feed.stops.size());
        
        for (Stop stop : feed.stops.values()) {
            Coordinate geom = new Coordinate(stop.stop_lon, stop.stop_lat); 
            stopsIndex.insert(new Envelope(geom), stop);
        }
    }
    
    /**
     * Index trips by route ID and direction, so that we can find them easily. 
     */
    private void indexTrips () {
        tripIndex = HashMultimap.create();
        
        for (Trip trip : feed.trips.values()) {
            // TODO: don't assume GTFS has a direction ID
            Direction dir = Direction.fromGtfs(trip.direction_id); 
            
            RouteDirection rd = new RouteDirection(trip.route, dir);
            
            tripIndex.put(rd, trip);
        }
    }
    
    /**
     * Index route directions by stop.
     */
    private void indexRouteStops () {
        routesByStop = HashMultimap.create();
        
        for (Trip trip : feed.trips.values()) {
            RouteDirection routeDir = new RouteDirection(trip.route, Direction.fromGtfs(trip.direction_id));
            
            Collection<StopTime> stopTimes = stopTimesForTrip(trip.trip_id);
            
            for (StopTime st : stopTimes) {
                routesByStop.put(feed.stops.get(st.stop_id), routeDir);
            }
        }
    }
    
    public Stop[] stopsForRouteDirecton (Route route, Direction direction) {
        return stopsForRouteDirection(new RouteDirection(route, direction));
    }
    
    /** Get a human readable name for a route direction in this feed */
    public String getName (RouteDirection dir) {
        Trip exemplar = tripIndex.get(dir).iterator().next();
        Collection<StopTime> stopTimes = stopTimesForTrip(exemplar.trip_id);
        String lastStopId = null;
        for (StopTime st : stopTimes) {
            lastStopId = st.stop_id;
        }
        
        return feed.stops.get(lastStopId).stop_name;
    }
    
    /**
     * Get the stops for a direction of a route, more or less in order.
     * "More or less" because a direction of a route may not always visit exactly the same stops in the same order.
     */
    public Stop[] stopsForRouteDirection(RouteDirection routeDirection) {
        Set<Stop> stops = new HashSet<Stop>();
        
        Collection<Trip> trips = tripIndex.get(routeDirection);
        
        // index the order of the stops
        String[][] tripStopOrder = new String[trips.size()][];
        
        
        // get all of the stops and put them in out of order; we will sort them momentarily
        int i = 0;
        for (Trip trip : trips) {
            // get all of the stop times
            Collection<StopTime> stopTimes = stopTimesForTrip(trip.trip_id);                    
            
            int j = 0;
            
            String[] stopOrder = new String[stopTimes.size()];
            
            for (StopTime st : stopTimes) {
                stops.add(feed.stops.get(st.stop_id));
                // intern the strings so we can use equality when scanning and not page more memory
                stopOrder[j++] = st.stop_id.intern();
            }
            
            tripStopOrder[i++] = stopOrder;
        }
        
        // sort the stops by which one appears appears first in the most trips
        Stop[] ret = stops.toArray(new Stop[stops.size()]);
        Arrays.sort(ret, new StopOrderComparator(tripStopOrder));
        
        return ret;
    }
    
    private Collection<StopTime> stopTimesForTrip(String trip_id) {
        return feed.stop_times.subMap(new Tuple2(trip_id, null), new Tuple2(trip_id, Fun.HI)).values();
    }

    /** get the geodetic distance between two points */
    public static final double getDistance(double lat0, double lon0, double lat1, double lon1) {
        // this is a needlessly verbose API and is also not thread safe
        GeodeticCalculator gc = new GeodeticCalculator();
        gc.setStartingGeographicPoint(lon0, lat0);
        gc.setDestinationGeographicPoint(lon1, lat1);
        return gc.getOrthodromicDistance();
    }
    
    /** get stops within threshold meters of the point */
    public Collection<Stop> stopsNear(final double lat, final double lon, final double threshold) {
        // convert the threshold to decimal degrees of latitude, which is easy
        // by definition, 10 000 000 m from equator to pole (it's actually more like 10 000 002, but who's counting?)
        double thresholdDegLat = threshold * 90 / 10000000;
        
        // more complicated as the length of a degree of longitude is not fixed . . .
        
        // 6 378 000 m: equatorial radius. use largest radius, err on side of caution.
        double radiusOfChordOfEarthAtThisLatitude = 6378000 * Math.cos(Math.toRadians(lat));
        
        double thresholdDegLon = threshold * 360 / (radiusOfChordOfEarthAtThisLatitude * Math.PI * 2);
        
        Envelope env = new Envelope(new Coordinate(lon, lat));
        env.expandBy(thresholdDegLon, thresholdDegLat);
        @SuppressWarnings("unchecked")
        Collection<Stop> potentialStops = stopsIndex.query(env);
        
        return Collections2.filter(potentialStops, new Predicate<Stop> () {
            @Override
            public boolean apply(Stop stop) {
                return getDistance(lat, lon, stop.stop_lat, stop.stop_lon) <= threshold;
            }
        });
    }
    
    /**
     * Get the optimal transfers for a route direction, in order from their transfer stops.
     * @param threshold maximum transfer distance, meters as the crow flies.
     */
    public Transfer[] getTransfers(RouteDirection dir, double threshold) {
        
        // get all of the stops for the route direction
        Stop[] stops = stopsForRouteDirection(dir);
        
        ArrayList<Transfer> transfers = new ArrayList<Transfer>();
        Multimap<Stop, Transfer> transfersByStop = HashMultimap.create();
        
        int i = 0;
        for (Stop fromStop : stops) {
            // loop over stops near this stop
            // TODO: don't hardwire threshold to 100m
            Map<RouteDirection, Transfer> bestTransfersForThisStop = new HashMap<RouteDirection, Transfer>();
            
            for (Stop toStop : stopsNear(fromStop.stop_lat, fromStop.stop_lon, 100)) {
                // find all possible transfers
                for (RouteDirection rd : routesByStop.get(toStop)) {
                    
                    // don't transfer to the same route, in this direction or the other.
                    if (rd.route.equals(dir.route))
                        continue;
                    
                    Transfer t = new Transfer(fromStop, toStop, dir, rd);
                    
                    // find one best transfer to every other route direction
                    if (bestTransfersForThisStop.containsKey(rd) && bestTransfersForThisStop.get(rd).distance < t.distance)
                        continue;
                    
                    bestTransfersForThisStop.put(rd, t);
                }
            }
            
            // add the best transfers to the indices
            for (Transfer t : bestTransfersForThisStop.values()) {
                transfers.add(t);
                transfersByStop.put(fromStop, t);
            }
            
            i++;
        }        
        
        // filter the transfers so that when there is a common trunk, we only have the first and last transfers
        for (int sidx = 1; sidx < stops.length - 1; sidx++) {
            Set<Transfer> transfersToRemove = new HashSet<Transfer>();
            
            // we are in a block of continuous transfers
            if (transfersByStop.containsKey(stops[sidx]) &
                    transfersByStop.containsKey(stops[sidx - 1]) &&
                    transfersByStop.containsKey(stops[sidx + 1])) {
                for (Transfer t : transfersByStop.get(stops[sidx])) {
                    boolean previous = false;
                    boolean next = false;
                    
                    for (Transfer prevt : transfersByStop.get(stops[sidx - 1])) {
                        if (prevt.toRouteDirection.equals(t.toRouteDirection)) {
                            previous = true;
                            break;
                        }
                    }
                    
                    for (Transfer nextt : transfersByStop.get(stops[sidx - 1])) {
                        if (nextt.toRouteDirection.equals(t.toRouteDirection)) {
                            next = true;
                            break;
                        }
                    }
                    
                    transfersToRemove.add(t);
                }
            }
            
            transfers.removeAll(transfersToRemove);
        }        
        
        return transfers.toArray(new Transfer[transfers.size()]);
    }
    
    /**
     * Get all of the transfer times for the given transfer in the feed.
     * TODO: constrain to specific day; currently this is looking at all the service in the feed as if it were a single day,
     * which is fine for the the TriMet use case as we use this in conjunction with calendar_extract. but in general this is not
     * desirable.
     */
    public TransferTime[] transferTimes(Transfer t) {
        // we can't just use an array, as not every trip stops at every stop
        // note
        TIntList arrivalTimes = new TIntArrayList(); 
        TIntList departureTimes = new TIntArrayList();
        
        for (Trip trip : tripIndex.get(t.fromRouteDirection)) {
            Iterator<StopTime> stopTimes = stopTimesForTrip(trip.trip_id).iterator();
            
            // doesn't make sense to transfer from the first stop on a trip, advance the iterator one
            // we handle this here rather than in findTransfers because the list of stops is only
            // somewhat in order. There could be transfers that make sense on some trips but not on others.
            
            // For instance, in DC, the northbound M4 runs from the Tenleytown Metro to Pinehurst, and sometimes
            // starts at Sibley Hospital. So it would make perfect sense to transfer to the metro from the northbound
            // M4 iff it was one of the trips that starts at Sibley Hospital rather than starting at the subway.
            if (stopTimes.hasNext())
                stopTimes.next();
            
            while (stopTimes.hasNext()) {
                StopTime st = stopTimes.next();
                if (st.stop_id.equals(t.fromStop.stop_id)) {
                    arrivalTimes.add(st.arrival_time);
                }
            }
        }
        
        for (Trip trip : tripIndex.get(t.toRouteDirection)) {
            Iterator<StopTime> stopTimes = stopTimesForTrip(trip.trip_id).iterator();
            while (stopTimes.hasNext()) {
                StopTime st = stopTimes.next();
                // doesn't make sense to transfer to the last stop on a trip, so if that's the case skip this one.
                if (st.stop_id.equals(t.toStop.stop_id) && stopTimes.hasNext()) {
                    departureTimes.add(st.departure_time);
                }
            }
        }
        
        arrivalTimes.sort();
        departureTimes.sort();
        
        List<TransferTime> transferTimes = new ArrayList<TransferTime>();
        
        TIntIterator arrivalsIterator = arrivalTimes.iterator();
        TIntIterator departuresIterator = departureTimes.iterator();
        
        if (!arrivalsIterator.hasNext() || !departuresIterator.hasNext())
            // no transfer
            // most likely we are either trying to transfer from the very start of a trip or the very end
            return new TransferTime[0];
        
        // this is outside the loop because the same departure can be the target for multiple arrivals.
        int departure = departuresIterator.next();
        
        // advance the arrivals iterator until we are looking at the last trip before the first departure
        // so we don't consider long "transfers" that are before the second route enters service
        TIntIterator otherArrivalsIterator = arrivalTimes.iterator();
        
        // otherArrivalsIterator is now one ahead of arrivals iterator; that is, it is a peeking iterator
        otherArrivalsIterator.next();
        
        while (otherArrivalsIterator.hasNext() && otherArrivalsIterator.next() < departure)
            arrivalsIterator.next();
        
        ARRIVALS: while (arrivalsIterator.hasNext()) {
            int arrival = arrivalsIterator.next();
            
            int earliestPossibleDeparture = arrival + minTransferTime + (int) Math.round(t.distance / walkSpeed);
            
            while (departure < earliestPossibleDeparture) {
                if (!departuresIterator.hasNext())
                    // no point in continuing, the remaining trips won't have transfers either
                    break ARRIVALS;
                
                departure = departuresIterator.next();
            }
            
            int transferTime = departure - arrival;
            
            if (transferTime <= maxTransferTime)
                transferTimes.add(new TransferTime(transferTime, arrival));
                
        }
        
        return transferTimes.toArray(new TransferTime[transferTimes.size()]);
    }
    
    /**
     * Calculate the distribution of transfer time statistics and add it to a transfer object.
     * @param fromTime the beginning of the time window to consider, in seconds
     * @param toTome the end of the time window to consider, in seconds
     */
    public void addDistributionToTransfer(Transfer t, int fromTime, int toTime) {
        TransferTime[] times = transferTimes(t);
        
        Arrays.sort(times, new TransferTime.LengthComparator());
        
        if (times.length == 0)
            return;
        
        // min and max are easy
        t.min = times[0].lengthOfTransfer;
        t.max = times[times.length - 1].lengthOfTransfer;
        
        // get the percentiles
        t.pct25 = getPercentile(25, times);
        t.median = getPercentile(50, times);
        t.pct75 = getPercentile(75, times);
        
        t.n = times.length;
    }
    
    
    /** Get a given percentile from a sorted list of times */
    private int getPercentile(int percent, TransferTime[] times) {
        if (times.length == 0)
            // by construction
            return Integer.MAX_VALUE;
        
        if (times.length == 1)
            return times[0].lengthOfTransfer;
        
        double offset = (((double) percent) / 100d) * ((double) times.length - 1);
        
        // we compute the percentile as a weighted average of the times above and below the offset
        // if we hit a number exactly, this will still work as we'll be taking the weighted average of the same
        // number
        int above = times[(int) Math.ceil(offset)].lengthOfTransfer;
        int below = times[(int) Math.floor(offset)].lengthOfTransfer;
        
        double aboveProportion = offset % 1;
        
        return (int) Math.round(aboveProportion * above + (1 - aboveProportion) * below);
    }


    /**
     * Shuffle the stops from many different trips together. We do this by putting stops that generally
     * appear before other stops first.
     * @author matthewc
     *
     */
    private static class StopOrderComparator implements Comparator<Stop> {
        private String[][] tripStopOrder;
        
        /**
         * Create a new stop order comparator with the given stop sequences. Stop IDs should be interned. 
         * @param tripStopOrder
         */
        public StopOrderComparator (String[][] tripStopOrder) {
            this.tripStopOrder = tripStopOrder;
        }
        
        public int compare(Stop s1, Stop s2) {
            // intern so we can use ==
            String id1 = s1.stop_id.intern();
            String id2 = s2.stop_id.intern();
            
            if (id1 == id2)
                return 0;
            
            int before = 0;
            int after = 0;
            
            for (String[] trip : tripStopOrder) {
                
                int s1idx = -1;
                int s2idx = -1;
                
                for (int i = 0; i < trip.length; i++) {
                    if (trip[i] == id1)
                        s1idx = i;
                    
                    else if (trip[i] == id2)
                        s2idx = i;
                    
                    if (s1idx >= 0 && s2idx >= 0)
                        // found both stops.
                        break;
                }
                
                if (s1idx >= 0 && s2idx >= 0) {
                    if (s1idx < s2idx) {
                        // s1 after s2
                        before++;
                    }
                    else {
                        // s2 after s1
                        after++;
                    }
                }
            }
            
            // before > after: s1 < s2
            return after - before;
        }
    }
    
    /**
     * The directions of routes in a feed. Directionality is a very agency-specific thing
     * (inbound? outbound? north? uphill? clockwise?). We just assume that there are two directions
     * that are distinct to the user. If available, we use the GTFS direction ID flag to define directions.
     * Otherwise, we infer them.
     */
    public static enum Direction {
        DIR_0, DIR_1;
        
        /** get a direction from a GTFS direction ID */
        public static Direction fromGtfs(int gtfsDirection) {
            return gtfsDirection == 0 ? DIR_0 : DIR_1;
        }
        
        public int toGtfs () {
            return this == DIR_0 ? 0 : 1;
        }
    }
    
    public static class RouteDirection {
        public Route route;
        public Direction direction;
        
        public RouteDirection(Route route, Direction direction) {
            this.route = route;
            this.direction = direction;
        }
        
        public boolean equals(Object o) {
            if (o instanceof RouteDirection) {
                RouteDirection ord = (RouteDirection) o;
                return direction.equals(ord.direction) && route.equals(ord.route);
            }
            
            return false;
        }
        
        public int hashCode () {
            return route.route_id.hashCode() + direction.ordinal();
        }
    }
    
    /**
     * Represents a transfer from a route direction to another route direction.
     * @author mattwigway
     *
     */
    public static class Transfer {
        public Stop fromStop;
        public Stop toStop;
        public RouteDirection fromRouteDirection;
        public RouteDirection toRouteDirection;
        /** meters, as the crow flies */
        public double distance;
        
        /** minimum transfer time, seconds */
        public int min;
        
        /** 25th percentile transfer time, seconds */
        public int pct25;
        
        /** median transfer time, seconds */
        public int median;
        
        /** 75th percentile transfer time, seconds */
        public int pct75;
        
        /** maximum transfer time, seconds */
        public int max;
        
        /** number of transfers */
        public int n;
        
        /** actual transfer times */
        public TransferTime[] transferTimes; 
        
        public Transfer(Stop fromStop, Stop toStop, RouteDirection fromRouteDirection, RouteDirection toRouteDirection) {
            this.fromStop = fromStop;
            this.toStop = toStop;
            this.fromRouteDirection = fromRouteDirection;
            this.toRouteDirection = toRouteDirection;
            this.distance = getDistance(fromStop.stop_lat, fromStop.stop_lon, toStop.stop_lat, toStop.stop_lon);
            
            // make it clear that these have not been initialized.
            min = pct25 = median = pct75 = max = n = Integer.MIN_VALUE;
        }
    }
    
    /** Represents a single instance of a transfer, with the length and the time of day */
    public static class TransferTime {
        /** Length of the transfer, seconds */
        public int lengthOfTransfer;
        
        /** time of day, seconds since midnight */
        public int timeOfDay;
        
        public TransferTime (int lengthOfTransfer, int timeOfDay) {
            this.lengthOfTransfer = lengthOfTransfer;
            this.timeOfDay = timeOfDay;
        }
        
        /** compare based on the length of the transfer */
        public static class LengthComparator implements Comparator<TransferTime> {
            @Override
            public int compare(TransferTime o1, TransferTime o2) {
                return o1.lengthOfTransfer - o2.lengthOfTransfer;
            }
        }
        
        /** compare based on the time of day */
        public static class TimeOfDayComparator implements Comparator<TransferTime> {
            @Override
            public int compare(TransferTime o1, TransferTime o2) {
                return o1.timeOfDay - o2.timeOfDay;
            } 
        }
    }
}
