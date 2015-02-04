/** Create a new pulsar instance for the given feed */
window.Pulsar = function (file) {
  this.file = file;
  this.height = 800;
  this.width = 1600;
  this.range = [0, 24];
};

window.Pulsar.prototype = {
  setRoute: function (route) {
    this.route = route;
    this.direction = 0;
    this.fetchData();
  },

  fetchData: function () {
    var instance = this;

    d3.json("transfers/" + this.file + "/" + this.route + "/" + this.direction, function (error, data) {
      instance.data = data;
      instance.redraw();
    });
  },

  toggleDirection: function () {
    this.direction = this.direction !== 0 ? 0 : 1;
    this.fetchData();
  },

  setRange: function (range) {
    this.range = range;
    this.redraw();
  },

  redraw: function () {
    var instance = this;

    // set the title
    d3.select("#title").text("Transfers from " + this.data[0].fromRouteDirection.route.route_short_name + " " +
      this.data[0].fromRouteDirection.route.route_long_name +
      " towards " + this.data[this.data.length - 1].fromStop.stop_name);

    // clear the plot
    d3.select("svg").remove();

    // draw the new plot
    // figure the spacing
    // we use data.length not data.length - 1 so that we have room for everything
    var yscale = d3.scale.linear()
      .range([0, this.height - 55])
      .domain([0, this.data.length]);

    // 250 is for text
    var xscale = d3.scale.linear()
      .domain([0, 90])
      .range([400, this.width - 10]);

    var svg = d3.select(".figure")
      .append('svg')
      .style("width", this.width + 'px')
      .style("height", this.height + 'px')
      .append('g');

    // append each transfer
    var transfers = svg.selectAll('g.transfer')
      .data(this.data);

    transfers
      .enter()
      .append('g')
      .attr('class', 'transfer')
      .attr('transform', function (d, i) {
        return 'translate(0 ' + yscale(i + 1) + ')';
      });

    transfers
      .append('text')
      .text(function (d) {
        var name = d.toRouteDirection.route.route_short_name != null ?
        d.toRouteDirection.route.route_short_name :
        d.toRouteDirection.route.route_long_name;
        return name + " at " + d.fromStop.stop_name;
      });

    var offset = -transfers[0][0].getBBox().height / 3;

    // add a line from each transfer so you can follow it across
    transfers.append('line')
      .attr('x1', xscale(0))
      .attr('x2', xscale(90))
      .attr('y1', offset)
      .attr('y2', offset)
      .attr('class', 'transfer-line');

    // hierarchical binding: see http://bost.ocks.org/mike/nest/
    var transferMarkers = transfers.selectAll('circle')
      .data(function (d, i) {
        // TODO: filter here
        var filtered = [];

        d.transferTimes.forEach(function (tt) {
          if (tt.timeOfDay >= instance.range[0] * 3600 && tt.timeOfDay <= instance.range[1] * 3600) {
            filtered.push(tt);
          }
        });

        return filtered;
      });

    transferMarkers.enter()
      .append('circle')
      .attr('class', 'transfer-marker')
      .attr('r', '3');

    transferMarkers
      .attr('cy', offset)
      .attr('cx', function (d) {
        return xscale(d.lengthOfTransfer / 60);
      });

      // set up the axis
      var axis = d3.svg.axis()
        .scale(xscale)
        .orient('bottom');

      svg.append('g')
        .attr('class', 'legend')
        .attr('transform', 'translate(0 ' + (this.height - 45) + ')')
        .call(axis);

      // add the label
      svg.append('g')
        .attr('class', 'label')
        .attr('transform', 'translate(' + (this.width / 2) + ' ' + (this.height - 4) + ')')
        .append('text')
        .text('Transfer length (minutes)');
  }
};
