package controllers;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.pulsar.TransferExtractor;
import com.conveyal.pulsar.TransferExtractor.Direction;
import com.conveyal.pulsar.TransferExtractor.RouteDirection;
import com.conveyal.pulsar.TransferExtractor.Transfer;
import com.conveyal.pulsar.TransferExtractor.TransferTime;

import play.*;
import play.libs.Json;
import play.mvc.*;
import views.html.*;

public class Application extends Controller {
    private static Map<String, TransferExtractor> transferExtractorPool = new HashMap<String, TransferExtractor>();
    
    // initialize the extractor pool
    static {
        for (File file : new File(Play.application().configuration().getString("gtfs-directory")).listFiles()) {
            if (!file.isDirectory() && file.getName().endsWith(".zip")) {
                String name = file.getName().replace(".zip", "");
                transferExtractorPool.put(name, new TransferExtractor(file));
            }
        }
    }
    
    public static Result index() {
        return ok(index.render("Your new application is ready."));
    }
    
    /** get all possible transfers for a given direction */
    public static Result transfers (String file, String route, int direction) {
        if (!transferExtractorPool.containsKey(file))
            return notFound("No such GTFS feed");
        
        TransferExtractor t = transferExtractorPool.get(file);
        
        Route r = t.feed.routes.get(route);
        
        if (r == null)
            return notFound("no such route");
        
        if (direction != 0 && direction != 1)
            return badRequest("direction must be 0 or 1");
       
        RouteDirection rd = new RouteDirection(r, Direction.fromGtfs(direction));

        // 100 meter threshold for transfers
        Transfer[] xfers = t.getTransfers(rd, 100);
        
        List<Transfer> ret = new ArrayList<Transfer>();
        
        for (Transfer xfer : xfers) {
            xfer.transferTimes = t.transferTimes(xfer);
            if (xfer.transferTimes.length > 0)
                ret.add(xfer);
        }
        
        return ok(Json.toJson(ret));
    }
    
    /** get all the routes for a given file */
    public static Result routes (String file) {
        if (!transferExtractorPool.containsKey(file))
            return notFound("No such GTFS feed");
        
        TransferExtractor t = transferExtractorPool.get(file);
        
        return ok(Json.toJson(t.feed.routes.values()));
    }
}
