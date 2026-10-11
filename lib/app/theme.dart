import 'package:flutter/material.dart';

const ink = Color(0xff172f3d);
const teal = Color(0xff176c68);
const ivory = Color(0xfffaf8f1);
const caution = Color(0xff80510f);

ThemeData mentorTheme() => ThemeData(
  useMaterial3: true,
  colorScheme: ColorScheme.fromSeed(
    seedColor: teal,
  ).copyWith(primary: teal, secondary: ink, surface: ivory, onSurface: ink),
  scaffoldBackgroundColor: ivory,
  appBarTheme: const AppBarTheme(
    backgroundColor: ivory,
    foregroundColor: ink,
    centerTitle: false,
  ),
  textTheme: const TextTheme(
    headlineLarge: TextStyle(
      fontSize: 34,
      height: 1.4,
      fontWeight: FontWeight.w800,
      color: ink,
    ),
    headlineMedium: TextStyle(
      fontSize: 28,
      height: 1.4,
      fontWeight: FontWeight.w700,
      color: ink,
    ),
    titleLarge: TextStyle(
      fontSize: 22,
      height: 1.5,
      fontWeight: FontWeight.w700,
      color: ink,
    ),
    titleMedium: TextStyle(
      fontSize: 17,
      height: 1.5,
      fontWeight: FontWeight.w700,
      color: ink,
    ),
    bodyLarge: TextStyle(fontSize: 16, height: 1.7, color: ink),
    bodyMedium: TextStyle(fontSize: 14, height: 1.6, color: ink),
    labelLarge: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
  ),
  cardTheme: CardThemeData(
    color: Colors.white,
    elevation: 0,
    margin: EdgeInsets.zero,
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(24),
      side: const BorderSide(color: Color(0xffe4e7df)),
    ),
  ),
  filledButtonTheme: FilledButtonThemeData(
    style: FilledButton.styleFrom(
      minimumSize: const Size(48, 52),
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
  ),
  outlinedButtonTheme: OutlinedButtonThemeData(
    style: OutlinedButton.styleFrom(
      minimumSize: const Size(48, 52),
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
  ),
  inputDecorationTheme: InputDecorationTheme(
    filled: true,
    fillColor: Colors.white,
    border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
  ),
  navigationBarTheme: const NavigationBarThemeData(
    backgroundColor: Colors.white,
    indicatorColor: Color(0xffd9ebe5),
  ),
);
