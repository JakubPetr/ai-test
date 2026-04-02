# Benchmark skriptovacích technologií pro Java systém

Projekt obsahuje jednoduchý benchmark pro 3 use case, každý spuštěný **1000x**:

1. **Krátký výraz** vracející ano/ne.
2. **Střední výraz** s validací a jednoduchým předvýpočtem.
3. **Dlouhý výraz** se simulací práce nad DB a ZIP payloadem (počítání `.xlsx` souborů + zápis výsledku).

## Technologie
- GraalVM Polyglot API + Python
- GraalVM Polyglot API + Espresso (jazyk `java`)
- Groovy přes JSR-223 ScriptEngine
- Janino
- Apache JEXL

## Jak spustit
```bash
mvn -q compile exec:java
```

Volitelně lze předat počet iterací:
```bash
mvn -q compile exec:java -Dexec.args="2000"
```

## Poznámky
- Skripty se kompilují / připravují jednou před měřením a pak se opakovaně volají.
- Pokud runtime neobsahuje potřebný jazyk (typicky Graal Python/Espresso), engine se označí jako `SKIPPED` a benchmark pokračuje dál.
