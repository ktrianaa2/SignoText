package com.example.signotext;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import android.os.Bundle;
import android.view.MenuItem;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class PaginaPrincipal extends AppCompatActivity {

    BottomNavigationView bottomNav;
    FragmentTransaction transaction;
    Fragment fragmentInicio, fragmentPerfil;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pagina_principal);


        fragmentInicio = new InicioFragment();
        fragmentPerfil = new PerfilFragment();
        getSupportFragmentManager().beginTransaction().add(R.id.fragmentContainer, fragmentInicio).commit();

        bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnNavigationItemSelectedListener(navListener);

        bottomNav.inflateMenu(R.menu.menu);


    }


    private BottomNavigationView.OnNavigationItemSelectedListener navListener = new BottomNavigationView.OnNavigationItemSelectedListener() {
        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            int itemId = item.getItemId();
            transaction = getSupportFragmentManager().beginTransaction();

            if (itemId == R.id.item_inicio) {
                fragmentInicio = new InicioFragment();
                transaction.replace(R.id.fragmentContainer, fragmentInicio);
            }  else if (itemId == R.id.item_perfil) {
                fragmentPerfil = new PerfilFragment();
                transaction.replace(R.id.fragmentContainer, fragmentPerfil);
            }
            transaction.addToBackStack(null);
            transaction.commit();
            return true;

        }
    };

}