package lixelv.parse_sign_for_prices.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.block.Block;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.block.entity.SignText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Parse_sign_for_pricesClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Регистрируем одну команду: /get_signs <args...>
        // Вся строка (regex + show_signs) парсится в один аргумент.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    literal("get_signs")
                            // Один "жадный" аргумент. Вся остальная строка уходит в него.
                            .then(argument("args", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        // Весь текст после /get_signs:
                                        String fullArgs = StringArgumentType.getString(context, "args").trim();
                                        return handleCommand(fullArgs);
                                    })
                            )
                            // Если вообще не ввели аргументов ("/get_signs"), попробуем пустую строку
                            .executes(context -> handleCommand(""))
            );
        });
        System.out.println("ParseSignForPricesClient initialized!");
    }

    /**
     * Разбираем вручную строку, чтобы получить:
     * 1) regex (первое слово)
     * 2) show_signs (true | false), если пользователь указал второе слово
     *
     * Пример:
     *  "/get_signs бан"            -> regex="бан", show_signs=true
     *  "/get_signs бан false"      -> regex="бан", show_signs=false
     *  "/get_signs \"(\d+)\s?аб\" false" -> regex="(\d+)\s?аб", show_signs=false
     */
    private int handleCommand(String fullArgs) {
        // Разбиваем по пробелам (но только "первые два токена"), чтобы оставить возможность
        // взять regex в кавычки
        // Пример: fullArgs="бан" или fullArgs="бан false" ...
        String regex;
        boolean showSigns = false; // по умолчанию

        if (fullArgs.isEmpty()) {
            // Если игрок не ввёл ничего после /get_signs
            regex = "";
        } else {
            String[] tokens = splitFirstTwoTokens(fullArgs);
            regex = tokens[0];

            // Если есть второй токен
            if (tokens[1] != null) {
                // Пытаемся понять, это "true" или "false"?
                if (tokens[1].equalsIgnoreCase("true")) {
                    showSigns = true;
                }
            }
        }

        // Дальше идёт логика, которая раньше была в doCommandLogic
        // 1) Компилируем regex
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (Exception e) {
            // Если REGEX некорректен
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                MutableText errorMsg = Text.literal("Некорректное регулярное выражение: ")
                        .append(Text.literal(regex).formatted(Formatting.YELLOW))
                        .formatted(Formatting.RED);
                client.player.sendMessage(errorMsg, false);
            }
            return 0;
        }

        // Вызываем "старый" метод, который обходит чанки и собирает таблички
        StringBuilder matchedSigns = new StringBuilder();
        int signCount = collectMatchingSigns(MinecraftClient.getInstance(), pattern, matchedSigns, showSigns);

        // Выводим результат
        outputResult(regex, signCount, matchedSigns);
        return 1;
    }

    /**
     * Маленький метод: делим строку на 2 части:
     *   - первая часть = всё до первого пробела (если есть)
     *   - вторая часть = всё, что осталось (если есть)
     * Пример: "бан false" -> ["бан", "false"]
     *         "бан"       -> ["бан", null]
     *         ""          -> ["",    null]
     */
    private String[] splitFirstTwoTokens(String full) {
        // Удаляем лишние пробелы в начале/конце (уже делали, но пусть будет)
        full = full.trim();
        if (full.isEmpty()) {
            return new String[]{"", null};
        }
        // Ищем первый пробел
        int spaceIndex = full.indexOf(' ');
        if (spaceIndex == -1) {
            // Значит вся строка — один токен
            return new String[]{full, null};
        } else {
            String first = full.substring(0, spaceIndex);
            String second = full.substring(spaceIndex + 1).trim();
            if (second.isEmpty()) second = null;
            return new String[]{first, second};
        }
    }

    /**
     * Форматный вывод результата.
     */
    private void outputResult(String regex, int signCount, StringBuilder matchedSigns) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (signCount > 0) {
            MutableText header = Text.literal("Табличек, подходящих под '")
                    .append(Text.literal(regex).formatted(Formatting.YELLOW))
                    .append(Text.literal("': " + signCount + "\n"))
                    .formatted(Formatting.GREEN);

            MutableText body = Text.literal(matchedSigns.toString()).formatted(Formatting.WHITE);
            header.append(body);

            client.player.sendMessage(header, false);

        } else {
            MutableText noMatch = Text.literal("Не нашли табличек, подходящих под '")
                    .append(Text.literal(regex).formatted(Formatting.YELLOW))
                    .append(Text.literal("'"))
                    .formatted(Formatting.RED);

            client.player.sendMessage(noMatch, false);
        }
    }


    /**
     * Класс для хранения данных о табличке (позиция, строки, цена).
     */
    private static class SignData {
        BlockPos pos;
        List<String> lines;
        int price;

        SignData(BlockPos pos, List<String> lines, int price) {
            this.pos = pos;
            this.lines = lines;
            this.price = price;
        }
    }

    /**
     * Полностью ваш "старый" метод обхода чанков:
     *  (\d+)\s?аб -> число *9
     *  (\d+)\s?а  -> число как есть
     *  сортировка, matchedSigns, etc.
     */
    private int collectMatchingSigns(
            MinecraftClient client,
            Pattern pattern,
            StringBuilder matchedSigns,
            boolean showSigns
    ) {
        GameOptions options = client.options;
        int viewDistance = options.getViewDistance().getValue();
        int originChunkX = client.player.getChunkPos().x;
        int originChunkZ = client.player.getChunkPos().z;
        int signCount = 0;

        // Список для финальной сортировки
        List<SignData> signDataList = new ArrayList<>();

        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                int chunkX = originChunkX + dx;
                int chunkZ = originChunkZ + dz;

                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(chunkX, chunkZ);
                if (chunk == null) continue;

                int startX = chunk.getPos().getStartX();
                int startZ = chunk.getPos().getStartZ();

                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < client.world.getHeight(); y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                            Block block = client.world.getBlockState(pos).getBlock();

                            if (block instanceof AbstractSignBlock) {
                                BlockEntity be = client.world.getBlockEntity(pos);
                                if (be instanceof SignBlockEntity signEntity) {
                                    SignText frontText = signEntity.getFrontText();
                                    Text[] lines = frontText.getMessages(false);

                                    List<String> lineList = new ArrayList<>();
                                    boolean matched = false;
                                    boolean foundPrice = false;
                                    int minPrice = Integer.MAX_VALUE;

                                    Pattern abPattern = Pattern.compile("(\\d+)\\s?аб");
                                    Pattern aPattern  = Pattern.compile("(\\d+)\\s?а");

                                    for (Text t : lines) {
                                        String lineStr = t.getString();
                                        if (!lineStr.isEmpty()) {
                                            lineList.add(lineStr);
                                        }

                                        // 1) Проверка пользовательского pattern
                                        Matcher userMatcher = pattern.matcher(lineStr);
                                        if (userMatcher.find()) {
                                            matched = true;
                                        }

                                        // 2) Ищем "аб" (*9)
                                        Matcher abMatcher = abPattern.matcher(lineStr);
                                        boolean hasAB = false;
                                        while (abMatcher.find()) {
                                            hasAB = true;
                                            foundPrice = true;
                                            int price = Integer.parseInt(abMatcher.group(1)) * 9;
                                            // проверяем, попадает ли price под pattern
                                            if (pattern.matcher(String.valueOf(price)).find()) {
                                                matched = true;
                                            }
                                            if (price < minPrice) {
                                                minPrice = price;
                                            }
                                        }

                                        // 3) Если нет "аб", проверяем "(\d+)\s?а"
                                        if (!hasAB) {
                                            Matcher aMatcher = aPattern.matcher(lineStr);
                                            while (aMatcher.find()) {
                                                foundPrice = true;
                                                int price = Integer.parseInt(aMatcher.group(1));
                                                if (pattern.matcher(String.valueOf(price)).find()) {
                                                    matched = true;
                                                }
                                                if (price < minPrice) {
                                                    minPrice = price;
                                                }
                                            }
                                        }
                                    }

                                    // Если не нашли ни одной цены, табличка отбрасывается
                                    if (!foundPrice) {
                                        matched = false;
                                    }

                                    // --- СТАРЫЙ КУСОК КОДА, НЕ УДАЛЯЕМ ---
                                    if (matched) {
                                        signCount++;
                                        matchedSigns.append("[")
                                                .append(pos.getX()).append(", ")
                                                .append(pos.getY()).append(", ")
                                                .append(pos.getZ())
                                                .append("] -> \n")
                                                .append(String.join("\n", lineList))
                                                .append("\n\n");
                                    }
                                    // --- КОНЕЦ СТАРОГО КОДА ---

                                    if (matched) {
                                        signDataList.add(new SignData(pos, lineList, minPrice));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Сортируем по возрастанию цены
        if (!signDataList.isEmpty()) {
            signDataList.sort(Comparator.comparingInt(sd -> sd.price));
            matchedSigns.setLength(0);
            signCount = signDataList.size();

            for (SignData sd : signDataList) {
                matchedSigns
                        .append("[")
                        .append(sd.pos.getX()).append(", ")
                        .append(sd.pos.getY()).append(", ")
                        .append(sd.pos.getZ())
                        .append("], цена=")
                        .append(sd.price);

                if (showSigns) {
                    matchedSigns
                            .append(" -> \n")
                            .append(String.join("\n", sd.lines))
                            .append("\n\n");
                } else {
                    matchedSigns.append("\n");
                }
            }
        }

        return signCount;
    }
}
