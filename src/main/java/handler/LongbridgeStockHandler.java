package handler;

import bean.StockBean;
import com.intellij.ide.util.PropertiesComponent;
import com.longport.Config;
import com.longport.ConfigBuilder;
import com.longport.quote.QuoteContext;
import com.longport.quote.SecurityQuote;
import com.longport.quote.SecurityStaticInfo;
import com.longport.quote.PrePostQuote;
import org.apache.commons.lang.StringUtils;
import utils.LogUtil;

import javax.swing.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 长桥API股票数据处理器
 * 支持港股和美股实时行情
 * 使用长桥OpenAPI Java SDK
 */
public class LongbridgeStockHandler extends StockRefreshHandler {
    private final JLabel refreshTimeLabel;
    private HashMap<String, String[]> codeMap;
    private List<String> longbridgeCodes;
    private Config config;
    private QuoteContext quoteContext;

    public LongbridgeStockHandler(JTable table, JLabel refreshTimeLabel) {
        super(table);
        this.refreshTimeLabel = refreshTimeLabel;
    }

    @Override
    public void handle(List<String> codes) {
        if (codes.isEmpty()) {
            return;
        }

        // 股票编码，英文分号分隔（成本价和成本接在编码后用逗号分隔）
        List<String> codeList = new ArrayList<>();
        codeMap = new HashMap<>();
        longbridgeCodes = new ArrayList<>();
        
        for (String str : codes) {
            String[] strArray;
            if (str.contains(",")) {
                strArray = str.split(",");
            } else {
                strArray = new String[]{str};
            }
            String originalCode = strArray[0];
            codeList.add(originalCode);
            codeMap.put(originalCode, strArray);
            
            // 转换为长桥格式
            String lbCode = convertToLongbridgeCode(originalCode);
            if (lbCode != null) {
                longbridgeCodes.add(lbCode);
            }
        }

        fetchQuotes();
    }

    /**
     * 将股票代码转换为长桥格式
     * 例如: hk00700 -> 700.HK, usAAPL -> AAPL.US, sh600519 -> 600519.SH
     */
    private String convertToLongbridgeCode(String code) {
        if (code == null || code.length() < 3) {
            return null;
        }
        
        String lowerCode = code.toLowerCase();
        if (lowerCode.startsWith("hk")) {
            // 港股: hk00700 -> 700.HK (去掉前导0)
            String stockCode = code.substring(2);
            // 去掉前导0
            while (stockCode.startsWith("0") && stockCode.length() > 1) {
                stockCode = stockCode.substring(1);
            }
            return stockCode + ".HK";
        } else if (lowerCode.startsWith("us")) {
            // 美股: usAAPL -> AAPL.US
            return code.substring(2).toUpperCase() + ".US";
        } else if (lowerCode.startsWith("sh")) {
            // 上海: sh600519 -> 600519.SH
            return code.substring(2) + ".SH";
        } else if (lowerCode.startsWith("sz")) {
            // 深圳: sz000001 -> 000001.SZ
            return code.substring(2) + ".SZ";
        }
        return null;
    }

    /**
     * 将长桥格式转回原始格式
     */
    private String convertFromLongbridgeCode(String lbCode) {
        if (lbCode == null || !lbCode.contains(".")) {
            return lbCode;
        }
        
        String[] parts = lbCode.split("\\.");
        if (parts.length != 2) {
            return lbCode;
        }
        
        String stockCode = parts[0];
        String market = parts[1].toUpperCase();
        
        switch (market) {
            case "HK":
                // 补齐到5位
                while (stockCode.length() < 5) {
                    stockCode = "0" + stockCode;
                }
                return "hk" + stockCode;
            case "US":
                return "us" + stockCode;
            case "SH":
                return "sh" + stockCode;
            case "SZ":
                return "sz" + stockCode;
            default:
                return lbCode;
        }
    }

    private void fetchQuotes() {
        if (longbridgeCodes.isEmpty()) {
            return;
        }

        PropertiesComponent instance = PropertiesComponent.getInstance();
        String appKey = instance.getValue("key_longbridge_app_key", "");
        String appSecret = instance.getValue("key_longbridge_app_secret", "");
        String accessToken = instance.getValue("key_longbridge_access_token", "");

        if (StringUtils.isEmpty(appKey) || StringUtils.isEmpty(appSecret) || StringUtils.isEmpty(accessToken)) {
            LogUtil.info("长桥API配置不完整，请在设置中配置 API Key, API Secret 和 Access Token");
            return;
        }

        try {
            // 初始化或更新Config和QuoteContext
            if (config == null || quoteContext == null) {
                // 使用ConfigBuilder创建Config
                ConfigBuilder configBuilder = new ConfigBuilder(appKey, appSecret, accessToken);
                configBuilder.httpUrl("https://openapi.longportapp.cn");
                config = configBuilder.build();
                
                // 使用静态方法create创建QuoteContext
                CompletableFuture<QuoteContext> contextFuture = QuoteContext.create(config);
                quoteContext = contextFuture.get(); // 等待创建完成
                LogUtil.info("长桥SDK配置已初始化");
            }

            // 转换为字符串数组
            String[] symbolStrings = longbridgeCodes.toArray(new String[0]);

            LogUtil.info("=== 长桥SDK请求 ===");
            LogUtil.info("Symbols: " + String.join(",", longbridgeCodes));

            // 使用SDK获取实时行情和静态信息
            long startTime = System.currentTimeMillis();
            CompletableFuture<SecurityQuote[]> quoteFuture = quoteContext.getQuote(symbolStrings);
            CompletableFuture<SecurityStaticInfo[]> staticInfoFuture = quoteContext.getStaticInfo(symbolStrings);
            
            // 等待异步结果
            SecurityQuote[] quotes = quoteFuture.get();
            SecurityStaticInfo[] staticInfos = staticInfoFuture.get();
            long endTime = System.currentTimeMillis();

            // 创建symbol到名称的映射
            Map<String, String> symbolToNameMap = new HashMap<>();
            for (SecurityStaticInfo info : staticInfos) {
                symbolToNameMap.put(info.getSymbol(), info.getNameCn());
            }

            LogUtil.info("=== 长桥SDK响应 ===");
            LogUtil.info("响应时间: " + (endTime - startTime) + "ms");
            LogUtil.info("获取到 " + quotes.length + " 条行情数据");
            LogUtil.info("==================");

            // 解析行情数据
            for (SecurityQuote quote : quotes) {
                parseQuote(quote, symbolToNameMap);
            }
            updateUI();
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            LogUtil.info("长桥SDK请求失败: " + errorMsg);
            
            LogUtil.info("异常堆栈: ");
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            LogUtil.info(sw.toString());
        }
    }

    private void parseQuote(SecurityQuote quote, Map<String, String> symbolToNameMap) {
        try {
            String symbol = quote.getSymbol();
            String originalCode = convertFromLongbridgeCode(symbol);
            
            StockBean bean = new StockBean(originalCode, codeMap);
            
            // 从静态信息中获取股票名称
            String name = symbolToNameMap.get(symbol);
            if (name != null && !name.isEmpty()) {
                bean.setName(name);
            } else {
                bean.setName(symbol);
            }

            // 当前价格
            String lastDone = "0";
            if (quote.getLastDone() != null) {
                lastDone = quote.getLastDone().toString();
            }
            bean.setNow(lastDone);

            // 昨收价
            String prevClose = "0";
            if (quote.getPrevClose() != null) {
                prevClose = quote.getPrevClose().toString();
            }

            // 计算涨跌和涨跌幅
            BigDecimal now = new BigDecimal(lastDone);
            BigDecimal yesterday = new BigDecimal(prevClose);
            if (yesterday.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal diff = now.subtract(yesterday);
                bean.setChange(diff.setScale(3, RoundingMode.HALF_UP).toString());
                
                BigDecimal percent = diff.divide(yesterday, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
                bean.setChangePercent(percent.toString());
            } else {
                bean.setChange("0");
                bean.setChangePercent("0");
            }

            // 最高价
            if (quote.getHigh() != null) {
                bean.setMax(quote.getHigh().toString());
            } else {
                bean.setMax(lastDone);
            }

            // 最低价
            if (quote.getLow() != null) {
                bean.setMin(quote.getLow().toString());
            } else {
                bean.setMin(lastDone);
            }

            // 解析盘前/盘后/夜盘价格
            String prePostPrice = parsePrePostPrice(quote);
            bean.setPrePostPrice(prePostPrice);

            // 更新时间 - 使用当前刷新时间，格式与其他handler保持一致
            // StockBean.getValueByColumn 会使用 substring(8) 截取时间部分
            java.time.LocalDateTime currentTime = java.time.LocalDateTime.now();
            String timeStr = currentTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            bean.setTime(timeStr);

            // 计算收益
            String costPriceStr = bean.getCostPrise();
            if (StringUtils.isNotEmpty(costPriceStr) && !costPriceStr.equals("--")) {
                try {
                    BigDecimal costPriceDec = new BigDecimal(costPriceStr);
                    BigDecimal incomeDiff = now.subtract(costPriceDec);
                    
                    if (costPriceDec.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal incomePercentDec = incomeDiff.divide(costPriceDec, 5, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(3, RoundingMode.HALF_UP);
                        bean.setIncomePercent(incomePercentDec.toString());
                    }

                    String bondStr = bean.getBonds();
                    if (StringUtils.isNotEmpty(bondStr) && !bondStr.equals("--")) {
                        BigDecimal bondDec = new BigDecimal(bondStr);
                        BigDecimal incomeDec = incomeDiff.multiply(bondDec)
                                .setScale(2, RoundingMode.HALF_UP);
                        bean.setIncome(incomeDec.toString());
                    }
                } catch (NumberFormatException e) {
                    // 忽略成本价格式错误
                }
            }

            updateData(bean);
        } catch (Exception e) {
            LogUtil.info("解析股票数据失败: " + e.getMessage());
        }
    }

    /**
     * 解析盘前/盘后/夜盘价格
     * 按顺序检查盘前、盘后、夜盘，哪个不为0就显示哪个
     */
    private String parsePrePostPrice(SecurityQuote quote) {
        try {
            // 检查盘前，如果不为null且价格不为0
            PrePostQuote preMarket = quote.getPreMarketQuote();
            if (preMarket != null && preMarket.getLastDone() != null) {
                BigDecimal prePrice = preMarket.getLastDone();
                if (prePrice.compareTo(BigDecimal.ZERO) != 0) {
                    return "盘前:" + prePrice.toString();
                }
            }
            
            // 检查盘后，如果不为null且价格不为0
            PrePostQuote postMarket = quote.getPostMarketQuote();
            if (postMarket != null && postMarket.getLastDone() != null) {
                BigDecimal postPrice = postMarket.getLastDone();
                if (postPrice.compareTo(BigDecimal.ZERO) != 0) {
                    return "盘后:" + postPrice.toString();
                }
            }
            
            // 检查夜盘，如果不为null且价格不为0
            PrePostQuote overnight = quote.getOvernightQuote();
            if (overnight != null && overnight.getLastDone() != null) {
                BigDecimal overnightPrice = overnight.getLastDone();
                if (overnightPrice.compareTo(BigDecimal.ZERO) != 0) {
                    return "夜盘:" + overnightPrice.toString();
                }
            }
        } catch (Exception e) {
            LogUtil.info("解析盘前/盘后/夜盘价格失败: " + e.getMessage());
        }
        return "--";
    }

    private void updateUI() {
        SwingUtilities.invokeLater(() -> {
            refreshTimeLabel.setText(LocalDateTime.now().format(TianTianFundHandler.timeFormatter));
            refreshTimeLabel.setToolTipText("最后刷新时间 (长桥API)");
        });
    }

    @Override
    public void stopHandle() {
        LogUtil.info("长桥股票行情刷新已停止");
    }
}

