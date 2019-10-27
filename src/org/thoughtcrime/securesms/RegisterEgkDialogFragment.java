package org.thoughtcrime.securesms;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;

/**
 * Fragment for users health card registration.
 */
public class RegisterEgkDialogFragment extends DialogFragment {

    //private static final String TAG = RegisterEgkDialogFragment.class.getSimpleName();

    private AlertDialog alert;
    private Button buttonRegister;

    public interface NoticeDialogListener {
        void onDialogPositiveClick(DialogFragment dialog);

        void onDialogNegativeClick(DialogFragment dialog);
    }

    // Use this instance of the interface to deliver action events
    NoticeDialogListener mListener;

    // Override the Fragment.onAttach() method to instantiate the NoticeDialogListener
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        Activity activity = null;

        if (context instanceof Activity){
            activity=(Activity) context;
        }

        // Verify that the host activity implements the callback interface
        try {
            // Instantiate the NoticeDialogListener so we can send events to the host
            mListener = (NoticeDialogListener) activity;
        } catch (ClassCastException e) {
            // The activity doesn't implement the interface, throw exception
            throw new ClassCastException(activity.toString()
                    + " must implement NoticeDialogListener");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        // Use the Builder class for convenient dialog construction
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

        // Get the layout inflater
        LayoutInflater inflater = getActivity().getLayoutInflater();

        @SuppressLint("InflateParams") View rootView = inflater.inflate(
                R.layout.fragment_register_egk_dialog, null);

        // the patient should only be able to register the EHC when he confirms the information given
        CheckBox cb = rootView.findViewById(R.id.checkbox);
        cb.setOnCheckedChangeListener((compoundButton, b) -> {
            if (b) {
                buttonRegister.setEnabled(true);
            } else {
                buttonRegister.setEnabled(false);
            }
        });

        // Inflate and set the layout for the dialog
        // Pass null as the parent view because its going in the dialog layout
        builder.setView(rootView); //inflater.inflate(R.layout.fragment_register_egk_dialog, null));
        builder.setTitle(getString(R.string.startup_dialog_title));
        builder.setMessage(getString(R.string.startup_dialog_text));
        builder.setPositiveButton(getString(R.string.startup_dialog_positive_button), (dialog, id) ->
                mListener.onDialogPositiveClick(RegisterEgkDialogFragment.this));
        builder.setNegativeButton(getString(R.string.startup_dialog_negative_button), (dialog, id) ->
                mListener.onDialogNegativeClick(RegisterEgkDialogFragment.this));

        // Create the AlertDialog object and return it
        alert = builder.create();

        return alert;
    }

    @Override
    public void onResume() {
        super.onResume();
        buttonRegister = alert.getButton(AlertDialog.BUTTON_POSITIVE);
        buttonRegister.setEnabled(false);
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        super.onCancel(dialog);
        mListener.onDialogNegativeClick(RegisterEgkDialogFragment.this);
    }
}