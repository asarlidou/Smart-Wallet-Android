package com.alphawallet.app.ui.boc;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.alphawallet.app.R;
import com.alphawallet.app.service.boc.BocAccountsService;
import com.alphawallet.app.util.boc.Account;
import com.alphawallet.app.util.boc.Utilities;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.observers.DisposableSingleObserver;
import io.reactivex.schedulers.Schedulers;

/**
 * Display list of available accounts (retrieved from Subscription ID) after user clicks on 'Get Accounts' of Main Activity
 * Starts AccountDetailsActivity if user clicks on a specific account ID from the list
 */
public class AccountActivity extends AppCompatActivity {

    private final String LOGTAG = this.getClass().getName();
    private Utilities utils = new Utilities();

    private CompositeDisposable disposable = new CompositeDisposable();
    private BocAccountsService mAccountsService = new BocAccountsService();

    private ProgressBar spinner;

    private ListView listView;
    private TextView textView;
    private ArrayList<String> accountListString;
    private String accountDetailsRaw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_activity);

        listView = findViewById(R.id.listView);
        textView = findViewById(R.id.list_text);
        // Get list of account IDs from Intent of Main Activity
        accountListString = getIntent().getStringArrayListExtra("list");

        spinner = findViewById(R.id.progressbar_boc);
        spinner.setVisibility(View.GONE);

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.list_item,  accountListString);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
                // Get subscription ID from application shared preferences
                String subId = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("SUB_ID", "NA");
                String accId=adapter.getItem(position);

                // Check if network is available and call the API to get details of an account
                if(utils.isNetworkAvailable(getApplicationContext())){
                    // Set loading spinner to active until we get response
                    spinner.setVisibility(View.VISIBLE);
                    getAccountDetails(accId, subId);

                }
                else{
                    Toast.makeText(getApplicationContext(),
                            "No network available, please connect !",
                            Toast.LENGTH_LONG).show();
                }

            }
        });
    }


    /**
     * BOC API call to get Account Details (RxJava call)
     */
    private void getAccountDetails(final String accId, final String subId) {
        disposable.add(
                mAccountsService
                        // Async call to BOC Java SDK library
                        .getAccountDetails(accId, subId)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeWith(new DisposableSingleObserver<List<Account>>() {
                            @Override
                            public void onSuccess(List<Account> response) {
                                // For debugging purposes
                                Log.e(LOGTAG, " ------------- Account success ---------------");
                                Log.e(LOGTAG, response.toString());

                                // Set loading spinner to invisible since we got success
                                spinner.setVisibility(View.GONE);
                                // Convert API response to ArrayList<String> to set the next activity view
                                accountDetailsRaw = response.toString();
                                // Start AccounDetailsActivity
                                Intent intent = new Intent(getApplicationContext(), AccountDetailsActivity.class);
                                intent.putStringArrayListExtra("details", responseToArrayList(response));
                                startActivity(intent);
                            }

                            @Override
                            public void onError(Throwable e) {
                                spinner.setVisibility(View.GONE);
                                Toast.makeText(getApplicationContext(), "An error has occured. please check your connection", Toast.LENGTH_LONG).show();
                                Log.e(LOGTAG, "onError: " + e.getMessage());
                            }
                        }));
    }


    /**
     * Convert the API response to ArrayList<String> that can be passed to AccountDetailsActivity for display
     * @param response
     * @return
     */
    private ArrayList<String> responseToArrayList(List<Account> response){
        ArrayList<String> responseArray = new ArrayList<>();
        responseArray.add(response.get(0).getBankId());
        responseArray.add(response.get(0).getAccountId());
        responseArray.add(response.get(0).getAccountName());
        responseArray.add(response.get(0).getAccountType());
        responseArray.add(response.get(0).getIBAN());
        responseArray.add(response.get(0).getCurrency());
        responseArray.add(response.get(0).getInfoTimeStamp());
        responseArray.add(response.get(0).getInterestRate().toString());
        responseArray.add(response.get(0).getMaturityDate());
        responseArray.add(response.get(0).getLastPaymentDate());
        responseArray.add(response.get(0).getNextPaymentDate());
        responseArray.add(response.get(0).getRemainingInstallments().toString());
        responseArray.add(response.get(0).getBalances().get(0).getAmount().toString());
        responseArray.add(response.get(0).getBalances().get(1).getAmount().toString());
        return responseArray;
    }



}
