(ns com.lemondronor.leaflet-gorilla
  "A renderer for Gorilla REPL that creates maps with leaflet."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [gorilla-renderable.core :as render]
            [selmer.parser :as selmer]))

(set! *warn-on-reflection* true)


(defn- uuid [] (str (java.util.UUID/randomUUID)))


;; We use [lat lon], but GeoJSON uses [lon lat].
(defn- transpose-coord [[lat lon]]
  [lon lat])


(defn multipoint-feature [coords]
  {:type :Feature
   :geometry {:type :MultiPoint
               :coordinates (map transpose-coord coords)}})


(defn linestring-feature [coords]
  {:type :Feature
   :geometry {:type :LineString
               :coordinates (map transpose-coord coords)}})


(defn polygon-feature [coords-arrays]
  {:type :Feature
   :geometry {:type :Polygon
               :coordinates (map #(map transpose-coord %) coords-arrays)}})


(defn- geojson-feature [geodesc]
  (let [type-desig (first geodesc)
        coords (second geodesc)]
    (case type-desig
      :points (multipoint-feature coords)
      :line (linestring-feature coords)
      :polygon (polygon-feature coords)
      ;; Default to :points
      (geojson-feature [:points geodesc]))))


(defn geojson-features [geometries]
  {:features
   (map geojson-feature geometries)})


(defn geojson [geometries]
  (json/write-str (geojson-features geometries)))


(defrecord LeafletView [geometries opts])


(defn- parse-args [args]
  (loop [args args
         geometries []
         options {}]
   (if (not (seq args))
     [geometries options]
     (let [arg (first args)
           rstargs (next args)]
       (if (keyword? arg)
         (if (seq rstargs)
           (recur (next rstargs)
                  geometries
                  (assoc options arg (first rstargs)))
           (throw (Exception. (str "No value specified for option " arg))))
         (recur rstargs
                (conj geometries arg)
                options))))))


(defn leaflet [& args]
  (let [[geometries opts] (parse-args args)]
    (LeafletView. geometries opts)))


(def default-options
  {:width 400
   :height 400
   :leaflet-js-url "http://cdn.leafletjs.com/leaflet-0.7.3/leaflet.js"
   :leaflet-css-url "http://cdn.leafletjs.com/leaflet-0.7.3/leaflet.css"
   :tile-layer-url "http://{s}.tile.osm.org/{z}/{x}/{y}.png"
   :color "steelblue"
   :opacity 1.0})


;; Use IDs for these tags that theoretically some other renderer could
;; reference?
(def leaflet-js-tag-id "leafjet-js")
(def leaflet-css-tag-id "leaflet-css")



(def content-template
  "<div>
<div id='{{map-id}}' style='height: {{height}}px; width: {{width}}px;'></div>
<script type='text/javascript'>
$(function () {
  var cachedScript = function(url, options) {
    // Allow user to set any option except for dataType, cache, and url
    options = $.extend( options || {}, {
      dataType: 'script',
      cache: true,
      url: url
    });

    // Use $.ajax() since it is more flexible than $.getScript
    // Return the jqXHR object so we can chain callbacks
    return jQuery.ajax(options);
  };
  var createMap = function() {
    console.log('Running createMap for {{map-id}}');
    var map = L.map('{{map-id}}')
    console.log('createMap ran for {{map-id}}');
    L.tileLayer('{{tile-layer-url}}')
        .addTo(map);
    var geoJson = L.geoJson(
      {{geojson}},
      {style: {'color': '{{color}}',
               'opacity': {{opacity}}}});
    geoJson.addTo(map);
    if ({{view}}) {
      map.setView.apply(map, {{view}});
    } else {
      map.fitBounds(geoJson.getBounds());
    }
  };
  if (!document.getElementById('{{css-tag-id}}')) {
    console.log('Adding css for {{map-id}}');
    $('<link>')
      .attr('rel', 'stylesheet')
      .attr('href', '{{leaflet-css-url}}')
      .attr('id', '{{css-tag-id}}')
      .appendTo('head');
  }
  if (!window.leafletJsLoaded) {
    if (!window.leafletJsIsLoading) {
      console.log('Adding js for {{map-id}}');
      window.leafletJsLoadedCallbacks = [createMap];
      window.leafletJsIsLoading = true;
      cachedScript('{{leaflet-js-url}}')
        .done(function() {
          console.log('js loaded');
          console.log('callbacks: ' + window.leafletJsLoadedCallbacks);
          window.leafletJsIsLoading = false;
          window.leafletJsLoaded = true;
          _.each(window.leafletJsLoadedCallbacks, function(cb) { cb(); });
          window.leafletJsLoadedCallbacks = [];
        })
        .fail(function() { console.log('failed'); });
    } else {
      console.log('Adding callback for {{map-id}}');
      window.leafletJsLoadedCallbacks.push(createMap);
    }
  } else {
    console.log('Calling createMap directly for {{map-id}}');
    createMap();
  }
});
</script>
</div>")


(extend-type LeafletView
  render/Renderable
  (render [self]
    (let [geometries (:geometries self)
          opts (:opts self)
          values (merge default-options
                        opts
                        {:js-tag-id leaflet-js-tag-id
                         :css-tag-id leaflet-css-tag-id
                         :map-id (uuid)
                         :view (json/write-str (:view opts))
                         :geojson [:safe (geojson geometries)]})
          html (selmer/render content-template values)]
      {:type :html
       :content html
       :value (pr-str self)})))


(comment

(defn fetch-url-lines[address]
  (with-open [stream (.openStream (java.net.URL. address))]
    (let  [buf (java.io.BufferedReader.
                (java.io.InputStreamReader. stream))]
      (line-seq buf))))

(def earthquakes
  (->> "http://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.csv"
       fetch-url-lines
       rest
       (map #(string/split % #","))))


(lg/leaflet (map (fn [e] [(e 1) (e 2)]) earthquakes))

(def oakland-alpr
  (->> "https://www.eff.org/files/2015/01/20/oakland_pd_alpr.csv"
       fetch-url-lines
       rest
       (map #(string/split % #","))))

(lg/leaflet (map (fn [r] [(r 2) (r 3)]) oakland-alpr))

)
