package com.conveyal.pulsar;

import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.geotools.referencing.GeodeticCalculator;
import org.mapdb.Fun;
import org.mapdb.Fun.Tuple2;
import org.openqa.selenium.firefox.internal.Streams;
import org.opentripplanner.graph_builder.impl.StreetlessStopLinker.StopAtDistance;

import akka.util.Collections;

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
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
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
        
        outfile.write("route_id,direction_id,destination,at,min,percentile_25,median,percentile_75,max\n");
        
        for (Transfer xfer : transfers) {
            if (++i % 50 == 0)
                LOG.info("processed " + (i - 1) + " transfers");
            
            t.addDistributionToTransfer(xfer);
          
            // TODO: quoting
            outfile.write(xfer.toRouteDirection.route.route_id + ",");
            outfile.write(xfer.toRouteDirection.direction.toGtfs() + ",");
            outfile.write("\"" + t.getName(xfer.toRouteDirection) + "\",");
            outfile.write("\"" + xfer.fromStop.stop_name + "\",");
            outfile.write(xfer.min + ",");
            outfile.write(xfer.pct25 + ",");
            outfile.write(xfer.median + ",");
            outfile.write(xfer.pct75 + ",");
            outfile.write(xfer.max + "\n");
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
        
        // keep track of one "best" transfer to every other route
        Map<RouteDirection, Transfer> bestTransfersByRoute = new HashMap<RouteDirection, Transfer>();
        final Map<RouteDirection, Integer> transferStopIndices = new HashMap<RouteDirection, Integer>();
        
        int i = 0;
        for (Stop fromStop : stops) {
            // loop over stops near this stop
            // TODO: don't hardwire threshold to 100m
            for (Stop toStop : stopsNear(fromStop.stop_lat, fromStop.stop_lon, 100)) {
                // find all possible transfers
                for (RouteDirection rd : routesByStop.get(toStop)) {
                    
                    // don't transfer to the same route, in this direction or the other.
                    if (rd.route.equals(dir.route))
                        continue;
                    
                    Transfer possibleTransfer = new Transfer(fromStop, toStop, dir, rd);
                    
                    if (bestTransfersByRoute.containsKey(rd) && bestTransfersByRoute.get(rd).distance < possibleTransfer.distance)
                        continue;
                    
                    bestTransfersByRoute.put(rd, possibleTransfer);
                    transferStopIndices.put(rd, i);
                }
            }
            
            i++;
        }
        
        Transfer[] ret = bestTransfersByRoute.values().toArray(new Transfer[bestTransfersByRoute.size()]);
        
        // sort the transfers by where they appear in the route
        Arrays.sort(ret, new Comparator<Transfer> () {

            @Override
            public int compare(Transfer o1, Transfer o2) {
                return transferStopIndices.get(o1.toRouteDirection) - transferStopIndices.get(o2.toRouteDirection);
            }
            
        });
        
        return ret;
    }
    
    /**
     * Get all of the transfer times for the given transfer in the feed.
     * TODO: constrain to specific day; currently this is looking at all the service in the feed as if it were a single day,
     * which is fine for the the TriMet use case as we use this in conjunction with calendar_extract. but in general this is not
     * desirable.
     */
    public int[] transferTimes(Transfer t) {
        // we can't just use an array, as not every trip stops at every stop
        // note
        TIntList arrivalTimes = new TIntArrayList(); 
        TIntList departureTimes = new TIntArrayList();
        
        for (Trip trip : tripIndex.get(t.fromRouteDirection)) {
            for (StopTime st : stopTimesForTrip(trip.trip_id)) {
                arrivalTimes.add(st.arrival_time);
            }
        }
        
        for (Trip trip : tripIndex.get(t.toRouteDirection)) {
            for (StopTime st : stopTimesForTrip(trip.trip_id)) {
                departureTimes.add(st.departure_time);
            }
        }
        
        arrivalTimes.sort();
        departureTimes.sort();
        
        TIntList transferTimes = new TIntArrayList();
        
        TIntIterator arrivalsIterator = arrivalTimes.iterator();
        TIntIterator departuresIterator = departureTimes.iterator();
        
        // this is outside the loop because the same departure can be the target for multiple arrivals.
        int departure = departuresIterator.next();
        
        // advance the arrivals iterator until we are looking at the last trip before the first departure
        TIntIterator otherArrivalsIterator = arrivalTimes.iterator();
        
        // otherArrivalsIterator is now one ahead of arrivals iterator; that is, it is a peeking iterator
        otherArrivalsIterator.next();
        
        while (otherArrivalsIterator.next() < departure)
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
                transferTimes.add(transferTime);
                
        }
        
        return transferTimes.toArray();
    }
    
    /**
     * Calculate the distribution of transfer time statistics and add it to a transfer object.
     */
    public void addDistributionToTransfer(Transfer t) {
        int[] times = transferTimes(t);
        
        Arrays.sort(times);
        
        if (times.length == 0)
            return;
        
        // min and max are easy
        t.min = times[0];
        t.max = times[times.length - 1];
        
        // get the percentiles
        t.pct25 = getPercentile(25, times);
        t.median = getPercentile(50, times);
        t.pct75 = getPercentile(75, times);
    }
    
    
    /** Get a given percentile from a sorted list of times */
    private int getPercentile(int percent, int[] times) {
        if (times.length <= 1)
            // by construction
            return Integer.MAX_VALUE;
        
        double offset = (((double) percent) / 100d) * ((double) times.length);
        
        // we compute the percentile as a weighted average of the times above and below the offset
        // if we hit a number exactly, this will still work as we'll be taking the weighted average of the same
        // number
        int above = times[(int) Math.ceil(offset)];
        int below = times[(int) Math.floor(offset)];
        
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
                    if (s1idx > s2idx) {
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
     * @author matthewc
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
        
        public Transfer(Stop fromStop, Stop toStop, RouteDirection fromRouteDirection, RouteDirection toRouteDirection) {
            this.fromStop = fromStop;
            this.toStop = toStop;
            this.fromRouteDirection = fromRouteDirection;
            this.toRouteDirection = toRouteDirection;
            this.distance = getDistance(fromStop.stop_lat, fromStop.stop_lon, toStop.stop_lat, toStop.stop_lon);
        }
    }
}
