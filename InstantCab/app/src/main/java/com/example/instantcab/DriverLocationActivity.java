package com.example.instantcab;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.fragment.app.FragmentActivity;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import com.google.firebase.firestore.CollectionReference;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;

import com.google.firebase.firestore.SetOptions;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;


import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import static android.icu.lang.UCharacter.GraphemeClusterBreak.T;
/**
 * This class is the activity driver sees after successfully logging in. It shows the map with driver's current location.
 * Drivers can view nearby requests by clicking corresponding markers on the map.
 * If driver clicks on accept_request button, details of the request will appear on another activity.
 *
 * @author peiyuan1
 */
public class DriverLocationActivity extends AppCompatActivity implements OnMapReadyCallback {

    Location currentLocation;
    FusedLocationProviderClient fusedLocationProviderClient;
    private static final int REQUEST_CODE = 101;
    private GoogleMap mMap;
    private Marker previousMarker = null;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser user;
    private Button btnAccept;
    private Button ButtonBack;
    private TextView textDest;
    private TextView textFare;
    private String TAG = "Rider at this marker is: ";
    public Request markerRequest = null;
    public static String userEmail;
    public boolean HasAcceptedRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_location);
        // Construct a FusedLocationProviderClient.
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        updateLocationUI();

        mAuth = FirebaseAuth.getInstance();
        user = mAuth.getCurrentUser();
        userEmail =user.getEmail();
        db = FirebaseFirestore.getInstance();
        DocumentReference docRef = db.collection("Driver's Request").document(userEmail);

        // check if driver has accepted a request
        DocumentReference docIdRef = db.collection("Driver's Request").document(userEmail);
        docIdRef.get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
            @Override
            public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                if (task.isSuccessful()) {
                    DocumentSnapshot document = task.getResult();
                    if (document.exists()) {
                        Log.d(TAG, "Document exists!");
                        // user already exists in db, button accept cannot be clicked
                        btnAccept = findViewById(R.id.accept_request);
                        btnAccept.setClickable(false);
                        HasAcceptedRequest = true;

                    } else {
                        Log.d(TAG, "Document does not exist!");
                        // Update one field, creating the document if it does not already exist.
                        Map<String, Object> data = new HashMap<>();
                        data.put("email", userEmail);
                        HasAcceptedRequest = false;
                        db.collection("Driver's Request").document(userEmail)
                                .set(data, SetOptions.merge());
                    }
                } else {
                    Log.d(TAG, "Failed with: ", task.getException());
                }
            }
        });

        // accept_request button
        btnAccept = findViewById(R.id.accept_request);
        btnAccept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //jump to DriverAcceptRequest Activity
                //if a marker was clicked before, information about the marker would be carried to new Activity
                if (markerRequest != null && !HasAcceptedRequest) {
                    HasAcceptedRequest = true;

                    Bundle bundle = new Bundle();
                    bundle.putString("from", markerRequest.getStartLocationName());
                    bundle.putString("to", markerRequest.getDestinationName());
                    bundle.putString("email", markerRequest.getEmail());
                    bundle.putString("fare", markerRequest.getFare());

                    saveLocalRequest(markerRequest);

                    //jump to different layouts based on request status
                    if (markerRequest.getStatus().equals("accepted")) {
                        Intent intent = new Intent(DriverLocationActivity.this, DriverAcceptRequest.class);
                        intent.putExtras(bundle);
                        startActivity(intent);}
                    else if (markerRequest.getStatus().equals("confirmed")) {
                        Intent intent = new Intent(DriverLocationActivity.this, RiderConfirmRequest.class);
                        intent.putExtras(bundle);
                        startActivity(intent);}
                    else {
                        markerRequest.setStatus("accepted");
                        markerRequest.setDriver(userEmail);
                        Intent intent = new Intent(DriverLocationActivity.this, DriverAcceptRequest.class);
                        intent.putExtras(bundle);
                        startActivity(intent);}
                }
            }
        });

        // if rider has confirmed request, a notification will appear on driver side
        if (markerRequest != null) {
            if (markerRequest.getStatus().equals("confirmed")) {
                showNotification();
            }
        }

        updateUI();

    }

    private void retrieveData() {
        // connect to firebase and load neighbouring markers
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        //final Map<String, Object> userInfo = new HashMap<>();
        db.collection("Request").addSnapshotListener(new EventListener<QuerySnapshot>() {
            @Override
            public void onEvent(@Nullable QuerySnapshot queryDocumentSnapshots, @Nullable FirebaseFirestoreException e) {
                if (queryDocumentSnapshots != null) {
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        Log.i("data", doc.getData().toString());

                        //double dest_lat = (double) doc.get("dest_lat");
                        //double dest_lon = (double) doc.get("dest_lon");
                        String email = (String) doc.get("email");
                        Double start_lat = doc.get("startLatitude", Double.class);
                        Double start_lon = doc.get("startLongitude", Double.class);
                        //userInfo.put("dest_lat",dest_lat);
                        //userInfo.put("dest_lon",dest_lon);
                        //userInfo.put("email",email);
                        //userInfo.put("start_lat",start_lat);
                        //userInfo.put("start_lon",start_lon);
                        createMarker(start_lat, start_lon, email);
                    }
                }
            }
        });
        //Log.i("info", userInfo.);
        return;
    }

    private void updateLocationUI() {
        /*get driver's current location if permission is granted by user;
        otherwise, no action.
         */
        if (ActivityCompat.checkSelfPermission(this.getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, new String[]
                    {Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CODE);
            return;}
        Task<Location> task = fusedLocationProviderClient.getLastLocation();
        task.addOnSuccessListener(new OnSuccessListener<Location>() {
            @Override
            public void onSuccess(Location location) {
                if (location != null) {
                    currentLocation = location;
                    //Log.i("current location",Double.toString(location.getLatitude()));
                    //Toast.makeText(getApplicationContext(), currentLocation.getLatitude()
                    //        +""+currentLocation.getLongitude(), Toast.LENGTH_SHORT).show();
                    SupportMapFragment supportMapFragment = (SupportMapFragment)
                            getSupportFragmentManager().findFragmentById(R.id.google_map);
                    supportMapFragment.getMapAsync(DriverLocationActivity.this);
                }
            }
        });

    }

    public  void updateUI() {
        if (user != null) {
            String email = user.getEmail();

            if (checkInternetConnectivity()) {
                DocumentReference docIdRef = db.collection("Driver's Request").document(email);
                docIdRef.get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                        if (task.isSuccessful()) {
                            DocumentSnapshot document = task.getResult();
                            if (document.exists()) {
                                Log.d(TAG, "You already accept a request!");
                                btnAccept.setClickable(false);
                            } else {
                                Log.d(TAG, "Failed with: ", task.getException());
                            }
                        } else {
                            Log.d(TAG, "Failed with: ", task.getException());
                        }
                    }
                });
                Log.i("have user", email);
            }
        }
    }

    private Boolean checkInternetConnectivity(){
        ConnectivityManager cm =
                (ConnectivityManager)this.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();

        return isConnected;
    }

    private void createMarker (double dest_lat, double dest_lon, String email) {
        //create a marker with a location and a title
        LatLng latLng = new LatLng(dest_lat, dest_lon);
        MarkerOptions markerOptions = new MarkerOptions().position(latLng).title(email);
        mMap.addMarker(markerOptions);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        //initialization google map
        mMap = googleMap;
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        //add markers into map
        retrieveData();
        //display driver's current position
        LatLng latLng = new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude());
        MarkerOptions markerOptions = new MarkerOptions().position(latLng).title("Driver Location");
        mMap.addCircle(new CircleOptions().center(latLng).radius(20).strokeColor(Color.RED).fillColor(Color.BLUE));
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15));
        mMap.addMarker(markerOptions);
        //click on marker event
        mMap.setOnMarkerClickListener(new GoogleMap.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(Marker marker) {
                // only one marker is allowed to be selected once. If a marker is selected, revert last marker back.
                if (previousMarker != null) {
                    previousMarker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
                }
                marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE));
                // click marker should edit textView below.
                String markerEmail = marker.getTitle();
                // marker selected should not be driver's own location marker
                if (!markerEmail.equals("Driver Location")){
                    DocumentReference docRef = db.collection("Request").document(markerEmail);
                        docRef.get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
                        @Override
                        public void onSuccess(DocumentSnapshot documentSnapshot) {
                            markerRequest = documentSnapshot.toObject(Request.class);
                            textDest = findViewById(R.id.destination);
                            textDest.setText(markerRequest.getDestinationName());
                            textFare = findViewById(R.id.fare);
                            textFare.setText(markerRequest.getFare());
                            Log.i(TAG, textDest.toString());
                        }
                    });
                }

                previousMarker = marker;
                return true;
            }
        });
    }

    //handle the result of the permission request
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults){
        switch (requestCode) {
            case REQUEST_CODE:
                if (grantResults.length>0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    updateLocationUI();
                }
                break;
        }
    }

    /*
    ideas from https://developer.android.com/guide/topics/ui/menus
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_driver, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.current_request && markerRequest == null) {
            setContentView(R.layout.activity_no_request);
            ButtonBack = findViewById(R.id.back);

            ButtonBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Clicked when the rider confirms his request
                    Intent intent = new Intent(DriverLocationActivity.this, DriverAcceptRequest.class);
                    startActivity(intent);
                }
            });
        }

        if (markerRequest != null) {
            if (item.getItemId() == R.id.current_request && markerRequest.getStatus().equals("accepted")) {
                Intent intent = new Intent(this, DriverAcceptRequest.class);
                startActivity(intent);
            }

            if (item.getItemId() == R.id.current_request && markerRequest.getStatus().equals("confirmed")) {
                Intent intent = new Intent(this, RiderConfirmRequest.class);
                startActivity(intent);
            }
        }
        if (markerRequest == null && item.getItemId() == R.id.current_request) {
            Log.d("Error", "You don't have active request");
        }
        else if (item.getItemId() == R.id.profile) {
            Intent intent = new Intent(this, DriverProfile.class);
            startActivity(intent);
        }

        else if (item.getItemId() == R.id.signOut){
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
        }
        else if(item.getItemId() == R.id.scan){
            Intent intent = new Intent(this, ScanQR.class);
            startActivity(intent);
        }

        return super.onOptionsItemSelected(item);
    }

    private void showNotification() {
        NotificationCompat.Builder builder = (NotificationCompat.Builder) new NotificationCompat.Builder(this)
                // build notification
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentTitle("Offer accepted")
                .setContentText("Your offer has been accepted, please pick up the rider");

        // show the notification
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1, builder.build());
    }

    private void loadLocalRequest(){
        SharedPreferences sharedPreferences = getSharedPreferences("localRequest", 0);
        Gson gson = new Gson();
        String json = sharedPreferences.getString(RiderMapsActivity.userEmail, "");
        Type type = new TypeToken<Request>() {}.getType();
        Request request = gson.fromJson(json, type);

        // check if there is any data
        if(request == null){
            // set text "no active request"
            setContentView(R.layout.activity_no_request);
            ButtonBack = findViewById(R.id.back);

            ButtonBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Clicked when the rider confirms his request
                    setContentView(R.layout.activity_driver_location);
                }
            });
        }
        else{
            String email = request.getEmail();
            // expect to send in the driver's name and also the fare
            String fare = request.getFare();
            // show the fare

            // show pick-up point and destination
            String destinationName = request.getDestinationName();
            String startLocationName = request.getStartLocationName();

            String driverName = request.getDriverName();
            Log.i("load1", driverName+"here status"+request.getStatus());

            if (request.getStatus() != null) {
//                displayDriver(driverEmail);
                Log.i("load2", driverName+"here status"+request.getStatus());
                String a = request.getStatus();
                switch (a) {
                    case "accepted":
                        Intent intent1 = new Intent(this, DriverAcceptRequest.class);
                        Bundle bundle1 = new Bundle();
                        bundle1.putString("from", startLocationName);
                        bundle1.putString("to", destinationName);
                        bundle1.putString("email", email);
                        bundle1.putString("fare", fare);
                        intent1.putExtras(bundle1);
                        startActivity(intent1);
                        break;
                    case "confirmed":
                        Intent intent2 = new Intent(this, RiderConfirmRequest.class);
                        Bundle bundle2 = new Bundle();
                        bundle2.putString("from", startLocationName);
                        bundle2.putString("to", destinationName);
                        bundle2.putString("email", email);
                        bundle2.putString("fare", fare);
                        intent2.putExtras(bundle2);
                        startActivity(intent2);
                        break;
//                    case "picked up":
//                        driverStatus.setText("Driver picked up rider");
//                        ButtonConfirmRequest.setVisibility(View.INVISIBLE);
//                        ButtonCancelRequest.setVisibility(View.INVISIBLE);
//                        ButtonPickedUp.setVisibility(View.INVISIBLE);
//                        ButtonArrive.setVisibility(View.VISIBLE);
//                        showDriver.setText(driverName);
//                        break;
//                    case "pending":
//                        driverStatus.setText("Waiting for driver to pick up");
//                        break;
                }
            }
            else{
                Log.i("load3", driverName+"here status"+request.getStatus());
            }
        }
    }

    public void saveLocalRequest(Request request){
        if (userEmail != null) {
            // User is signed in
            Log.i("have user", userEmail);

            SharedPreferences sharedPreferences = getSharedPreferences("localRequest", 0);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            Gson gson = new Gson();
            String json = gson.toJson(request);
            editor.putString(userEmail, json);
            editor.apply();
        } else {
            // No user is signed in
            Log.i("does not have user", "fail");
        }
    }

    public void updateLocalRequest(){
        db = FirebaseFirestore.getInstance();
        final String driverEmail;
        final String[] riderEmail = new String[1];
        final Request[] req = new Request[1];

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            driverEmail = user.getEmail();
        }
        else {driverEmail = "test@email.com";}

        CollectionReference driverRequests = db.collection("Driver's Request");
        final DocumentReference driverRequest = driverRequests.document(driverEmail);
        driverRequest.get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
            @Override
            public void onSuccess(DocumentSnapshot documentSnapshot) {
                riderEmail[0] = documentSnapshot.get("riderEmail", String.class);

                CollectionReference requests = db.collection("Request");
                final DocumentReference request = requests.document(riderEmail[0]);
                request.get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
                    @Override
                    public void onSuccess(DocumentSnapshot documentSnapshot) {
                        req[0] = documentSnapshot.toObject(Request.class);
                        if (req[0] == null) {

                        }
                        else {
                            SharedPreferences sharedPreferences = getSharedPreferences("localRequest", 0);
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            Gson gson = new Gson();
                            String json = gson.toJson(req[0]);
                            editor.putString(driverEmail, json);
                            editor.apply();
                        }
                    }
                });
            }
        });


    }

}
