# Media3, Compose, DataStore, and kotlinx-serialization ship their own consumer rules.
# The alphabet is read with Class.getResourceAsStream relative to MorseAlphabet's
# package, so keep that class's name (and so its package path) intact.
-keepnames class org.icculus.chunky.morsecar.morse.MorseAlphabet
