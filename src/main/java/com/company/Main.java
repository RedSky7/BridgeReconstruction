package com.company;

import com.github.davidmoten.rtree2.Entry;
import com.github.davidmoten.rtree2.Iterables;
import com.github.davidmoten.rtree2.RTree;
import com.github.davidmoten.rtree2.geometry.Geometries;
import com.github.davidmoten.rtree2.geometry.Geometry;
import com.github.davidmoten.rtree2.geometry.internal.PointDouble;
import com.github.mreutegg.laszip4j.LASHeader;
import com.github.mreutegg.laszip4j.LASPoint;
import com.github.mreutegg.laszip4j.LASReader;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import javax.vecmath.Vector3d;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.company.Main.TYPE.BRIDGES;
import static com.company.Main.TYPE.OTHER;

public class Main {

    private static final String INFRASTRUCTURE_FILE = "../GJI_SLO_SHP_G_1100_D48/GJI_SLO_1100_ILL_D48_20200508.shp";
    //private static final String INFRASTRUCTURE_FILE = "../GJI_SLO_SHP_G_1100/GJI_SLO_1100_ILL_20200402.shp";
    private static final String WATERS_FILE = "../DTM_HY_D48/HY_TEKOCE_VODE_L.shp";

    private static LASReader reader;

    private static RTree<Vector3d, Geometry> terrainTree;

    private static double scaleFactorX, scaleFactorY;

    private static GeometryFactory geometryFactory;


    public static void main(String[] args) throws FactoryException, IOException, TransformException {


        // Time needed for Delaunay triangulation of file GK_430_136.laz which contained 12M points was 40 minutes on Intel i7 6700K.
        reader = new LASReader(new File("GK_468_104.laz"));

        LASHeader lasHeader = reader.getHeader();
        scaleFactorX = lasHeader.getXScaleFactor();
        scaleFactorY = lasHeader.getYScaleFactor();

        geometryFactory = new GeometryFactory();

        terrainTree = RTree.create();



        double left = lasHeader.getMinX();
        double right = lasHeader.getMaxX();
        double top = lasHeader.getMinY();
        double bottom = lasHeader.getMaxY();

        Envelope2D bounds = new Envelope2D();
        bounds.x = left;
        bounds.y = top;
        bounds.width = right - left;
        bounds.height = bottom - top;
        //bounds.setCoordinateReferenceSystem(LAZ_CS);


        List<Bridge> bridges = readShapeFile(new File(INFRASTRUCTURE_FILE), "\"SIF_VRSTE\" = 1102 AND \"ATR2\" = 1", bounds, BRIDGES)
                .stream()
                .map(object -> ((Bridge) object))
                .collect(Collectors.toList());

        //"\"TIP_PREH\"=9999"
        //
        List<MultiLineString> others = readShapeFile(new File(WATERS_FILE), "\"HY_DTM_ID\"=458364", bounds, OTHER)
                .stream()
                .map(object -> ((MultiLineString) object))
                .collect(Collectors.toList());

        System.out.println("others = " + others.size());

        System.out.println("bridges = " + bridges.size());

        while(!mergeBridges(bridges)) {
            System.out.println("Repeat...");
        }
        System.out.println("bridges = " + bridges.size());

        int count = 0;
        for (Bridge bridge : bridges) {
            BoundingBox bridgeBounds = bridge.getSkirt();
            System.out.println("bridgeBounds = " + bridgeBounds.toString());

            //LASReader subread = reader.insideRectangle(bridgeBounds.getMinX(), bridgeBounds.getMinY(), bridgeBounds.getMaxX(), bridgeBounds.getMaxY());
            LASReader subread = reader.insideCircle(bridgeBounds.getMinX() + bridgeBounds.getWidth() / 2,
                    bridgeBounds.getMinY() + bridgeBounds.getHeight() / 2, Math.max(bridgeBounds.getWidth(), bridgeBounds.getHeight()) * 0.65);


            ArrayList<Vector3d> points = new ArrayList<>();
            for (LASPoint point : subread.getPoints()) {
                points.add(new Vector3d(point.getX(), point.getY(), point.getZ()));
            }


            // TODO: Get bridge width. Maybe even use meters.
            double BRIDGE_WIDTH = 12;

            ArrayList<Vector3d> bridgePoints = new ArrayList<>();

            // Classify point on the bridge as bridge points.
            for (Vector3d vector3D : points) {
                Point point = geometryFactory.createPoint(new Coordinate(vector3D.x * lasHeader.getXScaleFactor(), vector3D.y * lasHeader.getYScaleFactor()));

                boolean isTypeBridge = false;
                for(MultiLineString bridgeComponent : bridge.getBridges()) {
                    LineString partOfBridge = (LineString) bridgeComponent.getGeometryN(0);
                    Polygon bridgeBox = getBridgeBoundingBox(partOfBridge, BRIDGE_WIDTH);

                    if(bridgeBox.contains(point)) {
                        isTypeBridge = true;
                        break;
                    }
                }

                if(isTypeBridge) {
                    bridgePoints.add(vector3D);
                }
                else {
                    terrainTree = terrainTree.add(vector3D, PointDouble.create(vector3D.x, vector3D.y));
                }
            }

            System.out.println("bridgePoints count = " + bridgePoints.size());

            ArrayList<Vector3d> generatedPoints = new ArrayList<>();
            // TODO: Instead of this calculate a vector parallel to river, road and denstity at the end of bridge.
            // Then in steps go through and create points.
            for(MultiLineString bridgeComponent : bridge.getBridges()) {
                System.out.println("geometries = " + bridgeComponent.getNumGeometries());
                for (int i = 0; i < bridgeComponent.getNumGeometries(); i++) {
                    LineString partOfBridge = (LineString) bridgeComponent.getGeometryN(i);

                    Vector3d A = new Vector3d(partOfBridge.getStartPoint().getX(), partOfBridge.getStartPoint().getY(), 0);
                    Vector3d B = new Vector3d(partOfBridge.getEndPoint().getX(), partOfBridge.getEndPoint().getY(), 0);

                    Vector3d directionVector = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0);
                    double bridgeLength = directionVector.length();
                    directionVector.normalize();
                    Vector3d normal = getNormal(A, B);

                    // TODO: Get realDistance
                    Vector3d realVector = getDirectionVector(partOfBridge, others, normal);
                    double theta = realVector.angle(normal);
                    double realDistance = Math.sqrt(Math.pow(BRIDGE_WIDTH, 2) + Math.pow(Math.tan(theta) * BRIDGE_WIDTH, 2));
                    System.out.println("distance = " + BRIDGE_WIDTH);
                    System.out.println("new distance = " + realDistance);
                    double BRIDGE_STEP = 1;

                    System.out.println("bridge length = " + bridgeLength);
                    for (int j = 0; j < bridgeLength; j += BRIDGE_STEP) {
                        System.out.println("done = " + (j / bridgeLength));
                        Vector3d bridgePoint = new Vector3d(directionVector);

                        bridgePoint.scale(j);
                        bridgePoint.add(A);


                        // FIXME: STEP. TK
                        double STEP = 1;//density(bridgePoint, 10) * 10;
                        System.out.println("STEP = " + STEP);

                        System.out.println(lasHeader.getXScaleFactor());

                        for(double k = realDistance; k > 0; k -= STEP) {
                            traverseBridge(generatedPoints, bridgePoint, realVector, k);
                            traverseBridge(generatedPoints, bridgePoint, realVector, -k);
                        }

                        System.out.println(bridgeBounds.contains(bridgePoint.x, bridgePoint.y));

                        bridgePoint.x = bridgePoint.x * (1/scaleFactorX);
                        bridgePoint.y = bridgePoint.y * (1/scaleFactorY);
                        generatedPoints.add(new Vector3d(bridgePoint.x, bridgePoint.y, interpolateZ(bridgePoint)));
                    }
                }
            }

            points.addAll(generatedPoints);
            System.out.println("points = " + points.size());
            System.out.println("bridge points = " + bridgePoints.size());
            points.removeAll(bridgePoints);

            write(points, count++);
            //write(generatedPoints, count++);
            System.exit(0);
        }
    }

    private static Vector3d getDirectionVector(LineString lineString, List<MultiLineString> others, Vector3d fallBack) {

        // TODO: FIX this. TK
        for(MultiLineString multiLineString : others) {
            for(int i = 0; i < multiLineString.getNumGeometries(); i++) {
                LineString partOfBridge = (LineString) multiLineString.getGeometryN(i);
                //LineString partOfBridge = (LineString) multiLineString.getGeometryN(i);
                System.out.println("partOfBridge ? " + partOfBridge.getBoundary().toString());
                System.out.println("lineString ? " + lineString.getBoundary().toString());
                //if (partOfBridge.intersects(lineString)) {
                    Vector3d A = new Vector3d(partOfBridge.getStartPoint().getX(), partOfBridge.getStartPoint().getY(), 0);
                    Vector3d B = new Vector3d(partOfBridge.getEndPoint().getX(), partOfBridge.getEndPoint().getY(), 0);
                    Vector3d directionVector = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0);
                    directionVector.normalize();
                    System.out.println("FOUNDDDDD!!! dir = " + directionVector.toString());
                System.out.println("FOUNDDDDD!!! normal = " + fallBack.toString());

                return directionVector;
                //}
            }
        }
        System.out.println("NOT FOUNDD!!!");

        return fallBack;
    }

    private static Vector3d getNormal(Vector3d A, Vector3d B) {
        Vector3d directionVector = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0);
        directionVector.normalize();
        Vector3d normal = new Vector3d();
        normal.cross(directionVector, new Vector3d(0, 0, 1));
        normal.normalize();
        return normal;
    }

    private static Polygon getBridgeBoundingBox(LineString partOfBridge, double bridgeWidth) {
        Vector3d A = new Vector3d(partOfBridge.getStartPoint().getX(), partOfBridge.getStartPoint().getY(), 0);
        Vector3d B = new Vector3d(partOfBridge.getEndPoint().getX(), partOfBridge.getEndPoint().getY(), 0);

        Vector3d normal = getNormal(A, B);
        normal.scale(bridgeWidth);

        Vector3d negNormal = new Vector3d(normal);
        negNormal.scale(-1);

        Vector3d vector1 = addVectors(A, normal);
        Vector3d vector2 = addVectors(A, negNormal);
        Vector3d vector3 = addVectors(B, negNormal);
        Vector3d vector4 = addVectors(B, normal);

        Point point1 = geometryFactory.createPoint(new Coordinate(vector1.x, vector1.y));
        Point point2 = geometryFactory.createPoint(new Coordinate(vector2.x, vector2.y));
        Point point3 = geometryFactory.createPoint(new Coordinate(vector3.x, vector3.y));
        Point point4 = geometryFactory.createPoint(new Coordinate(vector4.x, vector4.y));

        return geometryFactory.createPolygon(new Coordinate[] {point1.getCoordinate(),
                point2.getCoordinate(),
                point3.getCoordinate(),
                point4.getCoordinate(),
        point1.getCoordinate()});
    }

    private static Vector3d addVectors(Vector3d vector1, Vector3d vector2) {
        Vector3d result = new Vector3d(vector1);
        result.add(vector2);
        return result;
    }

    private static void traverseBridge(ArrayList<Vector3d> generatedPoints,
                                Vector3d pointOnBridge,
                                Vector3d direction,
                                double k) {
        Vector3d bridgePoint = new Vector3d(pointOnBridge);

        Vector3d tempDirection = new Vector3d(direction);
        tempDirection.scale(k);
        bridgePoint.add(tempDirection);

        bridgePoint.x = bridgePoint.x * (1/scaleFactorX);
        bridgePoint.y = bridgePoint.y * (1/scaleFactorY);

        /*if(density(bridgePoint, 10) > 1)
            break;*/
        /*if(density(bridgePoint, generatedPoints, 20) > 0)
            return;*/

        Vector3d result = new Vector3d(bridgePoint.x, bridgePoint.y, interpolateZ(bridgePoint));
        generatedPoints.add(result);
        //System.out.println("result = " + result.toString());
        //terrainTree = terrainTree.add(result, PointDouble.create(result.x, result.y));
    }

    private static int density(Vector3d point, ArrayList<Vector3d> bridgePoints, int radius) {
        int count = 0;
        for(Vector3d bridgePoint : bridgePoints) {
            if(distance(bridgePoint, point) < radius)
                count++;
        }
        return count;
    }

    /*private static int density(Vector3d point, int radius) {
        Iterable<Entry<Vector3d, Geometry>> iterable = terrainTree.nearest(Geometries.point(point.x, point.y), radius, 10);
        int count = 0;
        while(iterable.iterator().hasNext()) {
            iterable.iterator().next();
            count++;
        }
        return count;
    }*/

    // Merge overlapping bridges
    private static boolean mergeBridges(List<Bridge> bridges) {
        for(int i = 0; i < bridges.size(); i++) {
            for(int j = i + 1; j < bridges.size(); j++) {
                Bridge bridge1 = bridges.get(i);
                Bridge bridge2 = bridges.get(j);
                if(bridge1.getSkirt().intersects(bridge2.getSkirt())) {
                    bridge1.getSkirt().include(bridge2.getSkirt());
                    bridge1.addBridges(bridge2.getBridges());
                    bridges.remove(bridge2);
                    return false;
                }
            }
        }
        return true;
    }

    private static double interpolateZ(Vector3d bridgePoint) {
        List<Entry<Vector3d, Geometry>> entries = Iterables.toList(terrainTree.nearest(Geometries.point(bridgePoint.x, bridgePoint.y), Double.MAX_VALUE, 40));
        double values = 0;
        double weightsSum = 0;

        // Sort by Z value.
        Collections.sort(entries, new Comparator<Entry<Vector3d, Geometry>>() {
            @Override
            public int compare(Entry<Vector3d, Geometry> o1, Entry<Vector3d, Geometry> o2) {
                /*System.out.println(o1.value().z);
                System.out.println(o2.value().z);*/
                if(o1.value().z < o2.value().z) {
                    return -1;
                }
                else if(o1.value().z > o2.value().z) {
                    return 1;
                }
                else
                    return 0;
            }
        });
        entries = entries.subList(0, 10);

        //System.out.println("size = " + entries.size());
        for (Entry<Vector3d, Geometry> entry : entries) {
            double distance = distance(entry.value(), bridgePoint);
            if(distance == 0)
                continue;

            double newWeight = 1 / distance;

            values += newWeight * entry.value().z;
            weightsSum += newWeight;
        }
        //System.out.println("values = " + values);
        //System.out.println("weightsum = " + weightsSum);

        double z =  values / weightsSum;
        //System.out.println("Z = " + z);
        return z;
    }

    private static double distance(Vector3d a, Vector3d b) {
        double distance = Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2));
        //System.out.println("distance = " + distance);
        return distance;
    }

    private static void write(ArrayList<Vector3d> points, int number) {
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(String.format("test%d.obj", number)), StandardCharsets.UTF_8))) {
            for(Vector3d lasPoint : points) {
                writer.write("v " + lasPoint.x + " " + lasPoint.y + " " + lasPoint.z + "\n");
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ArrayList<Object> readShapeFile(File file, String filter, Envelope2D bounds, TYPE type) {
        ArrayList<Object> result = new ArrayList<>();

        try {
            Map<String, String> connect = new HashMap();
            connect.put("url", file.toURI().toString());

            DataStore dataStore = DataStoreFinder.getDataStore(connect);
            String[] typeNames = dataStore.getTypeNames();
            String typeName = typeNames[0];

            System.out.println("Reading content " + typeName);

            FeatureSource featureSource = dataStore.getFeatureSource(typeName);

            // Only get the bridges.
            //String filterStatement = "\"SIF_VRSTE\" = 1102 AND \"ATR2\" = 1 AND ID = 17530217";

            FeatureCollection collection = featureSource.getFeatures();
            if(filter != null)
                collection = collection.subCollection(CQL.toFilter(filter));

            FeatureIterator iterator = collection.features();

            try {
                while (iterator.hasNext()) {
                    Feature feature = iterator.next();
                    GeometryAttribute sourceGeometry = feature.getDefaultGeometryProperty();


                    //if(bounds.intersects(sourceGeometry.getBounds()))

                    //System.out.println("type = " + sourceGeometry.getBounds().toString());
                    //System.out.println("bbox = " + bounds.toString());


                    CoordinateReferenceSystem coordinateReferenceSystem = sourceGeometry.getBounds().getCoordinateReferenceSystem();
                    BoundingBox bridgeBox = sourceGeometry.getBounds();
                    BoundingBox lasBox = bounds.toBounds(coordinateReferenceSystem);

                    if(bridgeBox.intersects(lasBox)) {
                        if(type == BRIDGES) {
                            System.out.println("FOUND!");
                            Bridge bridge = new Bridge();
                            bridge.addBridge((MultiLineString) sourceGeometry.getValue());
                            bridge.setSkirt(bridgeBox);
                            result.add(bridge);
                        }
                        else if(type == OTHER) {
                            result.add(sourceGeometry.getValue());
                        }
                    }
                }
            } finally {
                iterator.close();
            }


        }
        catch (Throwable e) {
            e.printStackTrace();
        }
        System.out.println("length = " + result.size());
        return result;
    }

    enum TYPE {
        BRIDGES,
        OTHER
    }


}
