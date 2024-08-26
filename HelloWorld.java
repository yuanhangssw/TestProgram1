package com.tianji.dam.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.tianji.dam.bean.GlobCache;
import com.tianji.dam.bean.SMSBean;
import com.tianji.dam.domain.*;
import com.tianji.dam.mapper.*;
import com.tianji.dam.mileageutil.Mileage;
import com.tianji.dam.scan.Pixel;
import com.tianji.dam.scan.Scan;
import com.tianji.dam.thread.*;
import com.tianji.dam.utils.MLTSMSutils;
import com.tianji.dam.utils.MapUtil;
import com.tianji.dam.utils.RandomUtiles;
import com.tianji.dam.utils.TrackConstant;
import com.tianji.dam.utils.productareapoints.scan.PointStep;
import com.tianji.dam.utils.productareapoints.scan.TransUtils;
import com.tianji.dam.websocket.WebSocketServer;
import com.tj.common.annotation.DataSource;
import com.tj.common.enums.DataSourceType;
import com.tj.common.utils.DateUtils;
import com.tj.common.utils.RGBHexUtil;
import com.tj.common.utils.StringUtils;
import com.tj.common.utils.uuid.UUID;
import com.vividsolutions.jts.algorithm.PointLocator;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.codec.binary.Base64;
import org.osgeo.proj4j.ProjCoordinate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.websocket.Session;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

@Service
@Slf4j
@DataSource(value = DataSourceType.SLAVE)
public class RollingDataService {
    public static final int CORECOUNT = 4;
    private static final Object obj1 = new Object();//锁
    @Autowired
    private static StoreHouseMap storeHouseMap;
    private static final Map<String, List<RealTimeRedisRightPopTaskBlockingZhuang>> threadMap = StoreHouseMap.getRealTimeRedisRightPopTaskBlockingZhuangList();
    private static final Map<String, StorehouseRange> shorehouseRange = StoreHouseMap.getShorehouseRange();
    private static final Map<String, RollingResult> rollingResultMap = StoreHouseMap.getRollingResultMap();
    Double division = 1d;
    @Autowired
    TColorConfigMapper colorConfigMapper;
    @Autowired
    TAnalysisConfigMapper tanalysisConfigMapper;

    @Autowired
    TSpeedWarmingMapper speedmapper;

    @Autowired
    TWarningUserMapper warningUserMapper;

    @Autowired
    private ITReportSaveService tReportSaveService;


    @Value("${imgPath}")
    private String imgPath;
    private final Map<String, Set<String>> globalThreadMap = new HashMap<>();
    private final Map<String, Set<String>> storehouseThreadList = new HashMap<>();
    private final Map<String, Set<String>> threadTimestamp = new HashMap<>();
    private final Map<String, String> threadStorehouse = new HashMap<>();
    @Autowired
    private CarMapper carMapper;
    @Autowired
    private TableMapper tableMapper;
    @Autowired
    private MaterialMapper materialMapper;
    @Autowired
    private WebSocketServer webSocketServer;
    Map<String, Session> users = WebSocketServer.getUsersMap();
    @Autowired
    private ITDamsconstructionReportService tDamsconstructionReportService;
    @Autowired
    private RollingDataMapper rollingDataMapper;
    @Autowired
    private TDamsconstructionMapper damsConstructionMapper;
    @Autowired
    private TRepairDataMapper repairDataMapper;
    @Autowired
    private TEncodeEvolutionMapper tEncodeEvolutionMapper;
    @Autowired
    private CangAllitemsMapper cangAllitemsMapper;

    @Autowired
    private SmsSendRecordMapper smsrecordMapper;


    private static final long TEN_MINUTES_IN_MILLIS = 10 * 60 * 1000;
    private static ConcurrentHashMap<String, Long> lastExecutionTime = new ConcurrentHashMap<>(); //测试点1

    int countTime0 = 0;
    int countTime1 = 0;
    int countTime01 = 0;
    int countTime02 = 0;


    public List<RollingData> getAllRollingData(String tableName) {
        return rollingDataMapper.getAllRollingData(tableName);
    }

    /**
     * 平面分析-单元工程--未使用了 2023年12月9日 10:27:46
     */
    public JSONObject getHistoryPicMultiThreadingByZhuangByTod(String tableName, Integer cartype) {

        JSONObject result = new JSONObject();
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));
        //工作仓 数据
        tableName = GlobCache.cartableprfix[cartype] + "_" + damsConstruction.getTablename();
        int dataSize;
        int alldatas = 0;
        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());
        //所有车
        List<Car> carList = carMapper.findCar();
        List<TRepairData> repairDataList = new ArrayList<>();
        //判断工作仓数据是否存在
        // boolean isHave=StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        long start = System.currentTimeMillis();
        //不存在则进行
        MatrixItem[][] matrix = new MatrixItem[xSize][ySize];
        for (int i = 0; i < xSize; i++) {
            for (int j = 0; j < ySize; j++) {
                matrix[i][j] = new MatrixItem();
                matrix[i][j].setRollingTimes(0);
            }
        }

        // if(2 == dmstatus) {
        shorehouseRange.put(id, storehouseRange);
        //  StoreHouseMap.getStoreHouses2RollingData().put(tableName, matrix);
        // }


        //2 获得数据库中的数据
        for (Car car : carList) {
            List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString());

            // 总数据条数
            dataSize = rollingDataList.size();
            alldatas += dataSize;
            System.out.println("即将处理》》" + dataSize + "条数据。。");

            //如果是摊铺 需要计算出所有数据的

            // 线程数 四个核心
            int threadNum = dataSize == 0 ? 1 : CORECOUNT;

            //每个线程处理的数据量
            int dataSizeEveryThread = dataSize / (threadNum);
            // 创建一个线程池
            ExecutorService exec = Executors.newFixedThreadPool(threadNum);

            // 定义一个任务集合
            List<Callable<Integer>> tasks = new LinkedList<>();
            CalculateGridForZhuangHistory task;
            List<RollingData> cutList;

            // 确定每条线程的数据
            for (int i = 0; i < threadNum; i++) {

                try {
                    if (i == threadNum - 1) {
                        int index = Math.max(dataSizeEveryThread * i - 1, 0);
                        cutList = rollingDataList.subList(index - 1, dataSize);
                    } else {
                        if (i == 0) {
                            cutList = rollingDataList.subList(0, dataSizeEveryThread * (i + 1));
                        } else {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i - 2, dataSizeEveryThread * (i + 1));
                        }
                    }
                    final List<RollingData> listRollingData = cutList;
                    task = new CalculateGridForZhuangHistory();
                    task.setRollingDataRange(rollingDataRange);
                    task.setRollingDataList(listRollingData);
                    task.setTableName(tableName);
                    task.setxNum(xSize);
                    task.setyNum(ySize);
                    task.setCartype(cartype);
                    task.setMatrix(matrix);
                    // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                    tasks.add(task);
                } catch (Exception e) {
                    System.out.println("仓位无数据");
                }
            }

            try {
                List<Future<Integer>> results = exec.invokeAll(tasks);
                for (Future<Integer> future : results) {
                    future.get();
                }
                // 关闭线程池
                exec.shutdown();
                log.info("线程任务执行结束");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        /*
          查询出当前仓位的补录区域
         */
        //repairDataList = gettRepairData(tableName, cartype, damsConstruction, rollingDataRange);

        log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");
        long g2time1 = System.currentTimeMillis();
        RollingResult rollingResult = new RollingResult();
        //超限
        RollingResult rollingResultSpeed = new RollingResult();
        //动静碾压
        RollingResult rollingResultVibrationD = new RollingResult();
        RollingResult rollingResultVibrationJ = new RollingResult();
        RollingResult rollingResultEvolution = new RollingResult();
        RollingResult rollingResultEvolution2 = new RollingResult();
        int count0 = 0;
        int count0Speed = 0;

        String bsae64_string = "";
        String bsae64_string_speed = "";
        String bsae64_string_vibrationD = "";
        String bsae64_string_vibrationJ = "";
        String bsae64_string_evolution = "";
        String bsae64_string_evolution2 = "";
        Map<Integer, Color> colorMap = getColorMap(GlobCache.carcoloconfigtype[cartype].longValue());

        TColorConfig vo = new TColorConfig();
        vo.setType(3L);//超限
        List<TColorConfig> colorConfigs = colorConfigMapper.select(vo);
        vo.setType(1L);//超限
        List<TColorConfig> colorConfigs1 = colorConfigMapper.select(vo);
        vo.setType(6L);//动静碾压
        List<TColorConfig> colorConfigs6 = colorConfigMapper.select(vo);
        vo.setType(44L);//摊铺平整度颜色
        List<TColorConfig> colorConfigs44 = colorConfigMapper.select(vo);
        vo.setType(45L);//摊铺厚度度颜色
        List<TColorConfig> colorConfigs45 = colorConfigMapper.select(vo);
        Map<Long, MatrixItem> reportdetailsmap = new HashMap<>();
        List<TDamsconstrctionReportDetail> allreportpoint = new LinkedList<>();
        synchronized (obj1) {// 同步代码块
            //绘制图片
            //得到图片缓冲区
            BufferedImage bi = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //超限次数
            BufferedImage biSpeed = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //动静碾压
            BufferedImage biVibrationD = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biVibrationJ = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biEvolution = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biEvolution2 = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //得到它的绘制环境(这张图片的笔)
            Graphics2D g2 = (Graphics2D) bi.getGraphics();

            //超限次数
            Graphics2D g2Speed = (Graphics2D) biSpeed.getGraphics();
            //动碾压
            Graphics2D g2VibrationD = (Graphics2D) biVibrationD.getGraphics();
            //静碾压
            Graphics2D g2VibrationJ = (Graphics2D) biVibrationJ.getGraphics();
            //平整度
            Graphics2D g2Evolution = (Graphics2D) biEvolution.getGraphics();
            Graphics2D g2Evolution2 = (Graphics2D) biEvolution2.getGraphics();
            List<Coordinate> list = JSONArray.parseArray(damsConstruction.getRanges(), Coordinate.class);
            Coordinate[] pointList = new Coordinate[list.size() + 1];  //最重要，不能遗漏，预先申请好数组空间
            list.toArray(pointList);
            pointList[list.size()] = pointList[0];
            //记录遍数
            GeometryFactory gf = new GeometryFactory();
            Geometry edgePoly = gf.createPolygon(pointList);
            long startTime = System.currentTimeMillis();

            //查询该区域是否存在试验点数据。
            TDamsconstructionReport treport = new TDamsconstructionReport();
            treport.setDamgid(Long.valueOf(damsConstruction.getId()));
            List<TDamsconstructionReport> allreport = tDamsconstructionReportService.selectTDamsconstructionReportList(treport);

            if (allreport.size() > 0) {
                for (TDamsconstructionReport tDamsconstructionReport : allreport) {
                    tDamsconstructionReport = tDamsconstructionReportService.selectTDamsconstructionReportByGid(tDamsconstructionReport.getGid());
                    List<TDamsconstrctionReportDetail> details = tDamsconstructionReport.gettDamsconstrctionReportDetailList();
                    allreportpoint.addAll(details);
                }
            }

            for (TDamsconstrctionReportDetail next : allreportpoint) {
                List<Coordinate> rlist = JSONArray.parseArray(next.getRanges(), Coordinate.class);
                if (rlist.size() == 1) {
                    Coordinate tempc = rlist.get(0);
                    double xxd = tempc.getOrdinate(0) - rollingDataRange.getMinCoordX();
                    int portx = (int) xxd;
                    double yyd = tempc.getOrdinate(1) - rollingDataRange.getMinCoordY();
                    int porty = (int) yyd;
                    if (null != matrix[portx][porty]) {
                        reportdetailsmap.put(next.getGid(), matrix[portx][porty]);
                    }
                }

            }


            //如果是摊铺推平 需要先计算出所有碾压区域的最后一个后点高程的平均值，然后通过与平均值的差值展示显示平整度

            Double begin_evolution = damsConstruction.getGaocheng();
            Double target_houdu = damsConstruction.getCenggao();
            //推平结果处理
            if (cartype == 2) {
                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            if (alldatas == 0) {
                                assert repairDataList != null;
                                if (repairDataList.size() == 0) {
                                    break;
                                }
                            }
                            try {
                                MatrixItem m = matrix[i][j];
                                if (null != m && m.getCurrentEvolution().size() > 0) {
                                    LinkedList<Float> evlist = m.getCurrentEvolution();
                                    Float currentevolution2 = evlist.size() == 0 ? 0f : evlist.getLast();
                                    float tphoudu = 100.0f * (currentevolution2 - begin_evolution.floatValue()) - target_houdu.floatValue();
                                    if (tphoudu != 0) {
                                        tphoudu = 8;
                                    }
                                    g2Evolution.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                    calculateRollingEvolution(tphoudu, rollingResult, colorConfigs44);
                                    g2Evolution.fillRect(i, j, 2, 2);
                                }


                            } catch (Exception e) {
                                log.error("出现错误。");
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                e.printStackTrace(new PrintStream(baos));
                                String exception = baos.toString();
                                log.error(exception);
                            }
                        }
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    ImageIO.write(biEvolution, "PNG", baos);
                    byte[] bytes = baos.toByteArray();//转换成字节
                    bsae64_string_evolution = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                    baos.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            } else if (cartype == 1) {//碾压结果处理
                String codekey = damsConstruction.getPid() + "_" + damsConstruction.getEngcode();
                List<Float> alllastgc = new ArrayList<>();
                if (GlobCache.encode_gc.containsKey(codekey)) {
                    alllastgc = GlobCache.encode_gc.get(codekey);
                }
                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            if (alldatas == 0) {
                                assert repairDataList != null;
                                if (repairDataList.size() == 0) {
                                    break;
                                }
                            }
                            count0++;
                            count0Speed++;
                            MatrixItem item = matrix[i][j];
                            Integer rollingTimes = item.getRollingTimes();

//                    if(rollingTimes==7){
//                        rollingTimes =8;
//                    }
                            g2.setColor(getColorByCount2(rollingTimes, colorMap));
                            calculateRollingtimes(rollingTimes, rollingResult);
                            g2.fillRect(i, j, 2, 2);

                            // TODO: 2021/10/21  超速次数
                            //获得速度集合
                            LinkedList<Float> speeds = matrix[i][j].getSpeedList();
                            if (speeds != null) {
                                g2Speed.setColor(getColorByCountSpeed(StringUtils.isNotEmpty(speeds) ? speeds.get(speeds.size() - 1) : new Float(-1), colorConfigs));
                                calculateRollingSpeed(StringUtils.isNotEmpty(speeds) ? speeds.get(speeds.size() - 1) : new Float(-1), rollingResultSpeed, colorConfigs);
                                g2Speed.fillRect(i, j, 2, 2);
                            }

                            try {
                                //Vibration 动静碾压
                                MatrixItem items2 = item;
                                if (null != items2) {
                                    //LinkedList<Double> vibrations = items2.getVibrateValueList();
                                    Integer vib = items2.getIsVibrate();
                                    Integer notvib = items2.getIsNotVibrate();
                                    Integer Dtarget = damsConstruction.getHeightIndex();
                                    if (null != Dtarget) {
                                        if (Dtarget.intValue() == damsConstruction.getFrequency().intValue()) {
                                            notvib = 0;
                                        }
                                    }

                                    // BigDecimal mi = new BigDecimal(vib*1.0/(notvib+vib)*100).setScale(2,RoundingMode.HALF_DOWN);
                                    //calculateRollingVibrate(notvib.doubleValue(), rollingResultVibration, colorConfigs1);
                                    calculateRollingDJ(notvib.doubleValue(), rollingResultVibrationJ, colorConfigs1);
                                    g2VibrationJ.setColor(getColorByCountVibrate(notvib.doubleValue(), colorConfigs1));
                                    g2VibrationJ.fillRect(i, j, 2, 2);

                                    calculateRollingDJ(vib.doubleValue(), rollingResultVibrationD, colorConfigs1);
                                    g2VibrationD.setColor(getColorByCountVibrate(vib.doubleValue(), colorConfigs1));
                                    g2VibrationD.fillRect(i, j, 2, 2);


//                                    if (null != vibrations && vibrations.size() > 0) {
//                                        try {
//                                            for (Double vcv : vibrations) {
//                                                //todo 动静碾压
//                                                if (StringUtils.isNotNull(vcv)) {
//                                                    calculateRollingVibrate(vcv, rollingResultVibration, colorConfigs6);
//                                                }
//                                            }
//                                            g2Vibration.setColor(getColorByCountVibrate(StringUtils.isNotEmpty(vibrations) ? Collections.max(vibrations) : new Double(-1), colorConfigs6));
//                                            g2Vibration.fillRect(i, j, 2, 2);
//                                        } catch (Exception ignored) {
//
//                                        }
//                                    }
                                }
                            } catch (Exception e) {
                                log.error("出现错误。");
                                e.printStackTrace();
                            }
                            //压实平整度、厚度
                            try {
                                MatrixItem m = matrix[i][j];
                                if (null != m && m.getElevationList().size() > 0) {
                                    LinkedList<Float> evlist = m.getElevationList();
                                    Float currentevolution2 = evlist.size() == 0 ? 0f : evlist.getLast();
                                    if (evlist.size() >= 5) {
                                        alllastgc.add(currentevolution2);
                                    }
                                    //平整度
                                    float tphoudu = (currentevolution2 - begin_evolution.floatValue());
                                    //厚度
                                    float tphoudu2 = 100.0f * (currentevolution2 - begin_evolution.floatValue());
//                                    if(tphoudu!=0){
//                                        tphoudu =8;
//                                    }
//                                    if(tphoudu2){
//
//                                    }

                                    g2Evolution.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                    calculateRollingEvolution(tphoudu, rollingResultEvolution, colorConfigs44);
                                    g2Evolution.fillRect(i, j, 2, 2);

                                    g2Evolution2.setColor(getColorByCountEvolution(tphoudu2, colorConfigs45));
                                    calculateRollingEvolution(tphoudu2, rollingResultEvolution2, colorConfigs45);
                                    g2Evolution2.fillRect(i, j, 2, 2);
                                }


                            } catch (Exception e) {
                                log.error("出现错误。");
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                e.printStackTrace(new PrintStream(baos));
                                String exception = baos.toString();
                                log.error(exception);
                            }


                        }
                    }
                }

                //将高程数据放回到缓存中
                Float minevolution = alllastgc.stream().min(Comparator.comparing(Float::floatValue)).get();
                Float maxevolution = alllastgc.stream().max(Comparator.comparing(Float::floatValue)).get();
                Double avgevolution = alllastgc.stream().mapToInt(Float::intValue).average().orElse(0);
                TEncodeEvolution query = new TEncodeEvolution();
                query.setAreagid(damsConstruction.getPid().longValue());
                query.setEncode(Long.valueOf(damsConstruction.getEngcode()));
                query.setDamgid(Long.valueOf(damsConstruction.getId()));
                List<TEncodeEvolution> savelist = tEncodeEvolutionMapper.selectTEncodeEvolutionList(query);
                if (savelist.size() == 0) {
                    TEncodeEvolution saveone = new TEncodeEvolution();
                    saveone.setAreagid(damsConstruction.getPid().longValue());
                    saveone.setDamgid(damsConstruction.getId().longValue());
                    saveone.setMaxEvolution(new BigDecimal(maxevolution));
                    saveone.setMinEvolution(new BigDecimal(minevolution));
                    saveone.setEvolution(new BigDecimal(avgevolution));
                    saveone.setEncode(Long.valueOf(damsConstruction.getEngcode()));
                    saveone.setNormalEvolution(BigDecimal.valueOf(damsConstruction.getGaocheng()));
                    saveone.setCreateTime(new Date());
                    saveone.setDelflag("N");
                    tEncodeEvolutionMapper.insertTEncodeEvolution(saveone);
                } else {
                    TEncodeEvolution saveone = savelist.get(0);

                    Double mind = new BigDecimal(minevolution + saveone.getMinEvolution().doubleValue()).divide(new BigDecimal(2), RoundingMode.HALF_DOWN).doubleValue();
                    Double maxd = new BigDecimal(maxevolution + saveone.getMaxEvolution().doubleValue()).divide(new BigDecimal(2), RoundingMode.HALF_DOWN).doubleValue();
                    Double avgd = new BigDecimal(avgevolution + saveone.getEvolution().doubleValue()).divide(new BigDecimal(2), RoundingMode.HALF_DOWN).doubleValue();

                    saveone.setUpdateTime(new Date());
                    saveone.setMaxEvolution(new BigDecimal(maxd));
                    saveone.setMinEvolution(new BigDecimal(mind));
                    saveone.setEvolution(new BigDecimal(avgd));

                    tEncodeEvolutionMapper.updateTEncodeEvolution(saveone);

                }

                // GlobCache.encode_gc.put(codekey,alllastgc);

                System.out.println("矩形区域内一共有点" + count0 + "个");
                //count0表示所有属于当前单元工程的轨迹点 time0表示在计算完遍数后，该单元工程内没有碾压的数据
                int time0 = count0 - rollingResult.getTime1() - rollingResult.getTime2() - rollingResult.getTime3() - rollingResult.getTime4() - rollingResult.getTime5() - rollingResult.getTime6() - rollingResult.getTime7() - rollingResult.getTime8()
                        - rollingResult.getTime9() - rollingResult.getTime10() - rollingResult.getTime11() - rollingResult.getTime11Up();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0 <= 0) {
                    time0 = 0;
                }
                rollingResult.setTime0(time0);

                //速度超限
                int time0Speed = count0Speed - rollingResultSpeed.getTime1() - rollingResultSpeed.getTime2();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0Speed <= 0) {
                    time0Speed = 0;
                }
                rollingResultSpeed.setTime0(time0Speed);

                long endTime = System.currentTimeMillis();    //获取结束时间
                log.info("程序运行时间：" + (endTime - startTime) + "ms");    //输出程序运行时间
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                //超限
                ByteArrayOutputStream baosSpeed = new ByteArrayOutputStream();
                //动碾压
                ByteArrayOutputStream baosVibrationD = new ByteArrayOutputStream();
                //静碾压
                ByteArrayOutputStream baosVibrationJ = new ByteArrayOutputStream();
                //碾压平整度
                ByteArrayOutputStream baoev = new ByteArrayOutputStream();
                //碾压厚度
                ByteArrayOutputStream baoev2 = new ByteArrayOutputStream();
                //        bi = (BufferedImage) ImgRotate.imageMisro(bi,0);
                try {
                    //遍数
                    ImageIO.write(bi, "PNG", baos);
                    byte[] bytes = baos.toByteArray();//转换成字节
                    bsae64_string = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                    baos.close();
                    try {
                        //超限
                        ImageIO.write(biSpeed, "PNG", baosSpeed);
                        byte[] bytesSpeed = baosSpeed.toByteArray();//转换成字节
                        bsae64_string_speed = "data:image/png;base64," + Base64.encodeBase64String(bytesSpeed);
                        baosSpeed.close();
                        //动静碾压
                        ImageIO.write(biVibrationD, "PNG", baosVibrationD);
                        byte[] bytesVibrationD = baosVibrationD.toByteArray();//转换成字节
                        bsae64_string_vibrationD = "data:image/png;base64," + Base64.encodeBase64String(bytesVibrationD);
                        baosVibrationD.close();

                        //动静碾压
                        ImageIO.write(biVibrationJ, "PNG", baosVibrationJ);
                        byte[] bytesVibrationJ = baosVibrationJ.toByteArray();//转换成字节
                        bsae64_string_vibrationJ = "data:image/png;base64," + Base64.encodeBase64String(bytesVibrationJ);
                        baosVibrationJ.close();

                        //平整度
                        ImageIO.write(biEvolution, "PNG", baoev);
                        byte[] byteev = baoev.toByteArray();//转换成字节
                        bsae64_string_evolution = "data:image/png;base64," + Base64.encodeBase64String(byteev);
                        baoev.close();
                        //碾压厚度
                        ImageIO.write(biEvolution2, "PNG", baoev2);
                        byte[] byteev2 = baoev2.toByteArray();//转换成字节
                        bsae64_string_evolution2 = "data:image/png;base64," + Base64.encodeBase64String(byteev2);
                        baoev.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }


                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        long g2time2 = System.currentTimeMillis();
        System.out.println("生成图片耗时：" + (g2time2 - g2time1) / 1000);
        StoreHouseMap.getStoreHouses2RollingData().remove(tableName);

        ProjCoordinate projCoordinate1 = new ProjCoordinate(rollingDataRange.getMinCoordY(), rollingDataRange.getMinCoordX(), 10);
        ProjCoordinate projCoordinate2 = new ProjCoordinate(rollingDataRange.getMaxCoordY(), rollingDataRange.getMaxCoordX(), 10);

        result.put("rollingResult", rollingResult);
        result.put("rollingResultSpeed", rollingResultSpeed);
        result.put("rollingResultVibrationD", rollingResultVibrationD);
        // result.put("rollingResultVibrationD", rollingResult);
        result.put("rollingResultVibrationJ", rollingResultVibrationJ);
        result.put("rollingResultEvolution", rollingResultEvolution);
        result.put("rollingResultEvolution2", rollingResultEvolution2);
        result.put("base64", bsae64_string);
        result.put("base64Speed", bsae64_string_speed);
        //  result.put("base64VibrationD", bsae64_string_vibrationD);
        result.put("base64VibrationD", bsae64_string);
        result.put("base64VibrationJ", bsae64_string_vibrationJ);
        result.put("base64Evolution", bsae64_string_evolution);
        result.put("base64Evolution2", bsae64_string_evolution2);
        result.put("rollingDataRange", rollingDataRange);
        result.put("pointLeftBottom", projCoordinate1);
        result.put("pointRightTop", projCoordinate2);
        result.put("height", damsConstruction.getGaocheng());
        result.put("cenggao", damsConstruction.getCenggao());
        result.put("range", range);
        result.put("reportdetailsmap", reportdetailsmap);
        result.put("allreportpoint", allreportpoint);
        //边界
        result.put("ranges", damsConstruction.getRanges());
        result.put("id", damsConstruction.getId());
        List<JSONObject> images = new LinkedList<>();
        result.put("images", images);
        return result;
    }

    /**
     * 通过每一层的平均高层去统计计算厚度
     *
     * @param tableName
     * @param cartype
     * @return
     */
    public JSONObject getHistoryPicMultiThreadingByZhuangByTodNews(String tableName, Integer cartype) {

        JSONObject result = new JSONObject();
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));


        //工作仓 数据
        tableName = GlobCache.cartableprfix[cartype] + "_" + damsConstruction.getTablename();
        int dataSizetotal = 0;
        //查询所有数据的平均高程 用以计算平整度：
        Double avgevolutiontop = damsConstructionMapper.getdamavgevolution(tableName);
        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());
        //所有车
        List<Car> carList = carMapper.findCar();
        List<TRepairData> repairDataList = null;
        //判断工作仓数据是否存在
        // boolean isHave=StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        long start = System.currentTimeMillis();
        //不存在则进行
        MatrixItemNews[][] matrix = new MatrixItemNews[xSize][ySize];
        for (int i = 0; i < xSize; i++) {
            for (int j = 0; j < ySize; j++) {
                matrix[i][j] = new MatrixItemNews();
            }
        }

        // if(2 == dmstatus) {
        shorehouseRange.put(id, storehouseRange);
        // }
        int threadNum = CORECOUNT;
        ExecutorService exec = Executors.newFixedThreadPool(threadNum);
        // 定义一个任务集合
        List<Callable<Integer>> tasks = new LinkedList<>();
        //2 获得数据库中的数据
        for (Car car : carList) {
            if (!car.getType().equals(cartype)) {
                continue;
            }
            List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString());

            // 总数据条数
            int dataSize = rollingDataList.size();
            dataSizetotal += dataSize;
            if (dataSize == 0) {
                continue;
            }
            System.out.println("即将处理》》" + dataSize + "条数据。。");

            //如果是摊铺 需要计算出所有数据的

            //每个线程处理的数据量
            int dataSizeEveryThread = dataSize / (threadNum);
            // 创建一个线程池


            Callable<Integer> task = null;
            List<RollingData> cutList = null;

            // 确定每条线程的数据
            for (int i = 0; i < threadNum; i++) {

                try {
                    if (i == threadNum - 1) {
                        int index = Math.max(dataSizeEveryThread * i - 1, 0);
                        cutList = rollingDataList.subList(index - 1, dataSize);
                    } else {
                        if (i == 0) {
                            cutList = rollingDataList.subList(0, dataSizeEveryThread * (i + 1));
                        } else {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i - 2, dataSizeEveryThread * (i + 1));
                        }
                    }
                    final List<RollingData> listRollingData = cutList;
                    task = new CalculateGridForZhuangHistoryNews();
                    ((CalculateGridForZhuangHistoryNews) task).setRollingDataRange(rollingDataRange);
                    ((CalculateGridForZhuangHistoryNews) task).setRollingDataList(listRollingData);
                    ((CalculateGridForZhuangHistoryNews) task).setTableName(tableName);
                    ((CalculateGridForZhuangHistoryNews) task).setxNum(xSize);
                    ((CalculateGridForZhuangHistoryNews) task).setyNum(ySize);
                    ((CalculateGridForZhuangHistoryNews) task).setCartype(cartype);
                    ((CalculateGridForZhuangHistoryNews) task).setTempmatrix(matrix);
                    // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                    tasks.add(task);
                } catch (Exception e) {
                    System.out.println("仓位无数据");
                }
            }
            try {
                List<Future<Integer>> results = exec.invokeAll(tasks);
                for (Future<Integer> future : results) {
                    future.get();
                }
                // 关闭线程池
                log.info("线程任务执行结束");
            } catch (Exception e) {
                e.printStackTrace();
            }
            tasks.clear();
        }
        exec.shutdown();

        System.out.println(xSize + ">" + ySize);
        List<CangAllitems> allitemsList_cang = new ArrayList<>();
        List<CangAllitems> allitemsList_ceng = new ArrayList<>();

        String tablename_cang = "cang_allitems_" + damsConstruction.getTablename();

        int ishave_cang = cangAllitemsMapper.checktable(tablename_cang);
        if (ishave_cang == 0) {
            cangAllitemsMapper.createtable(tablename_cang);
            cangAllitemsMapper.createindexs(tablename_cang);
        } else {
            cangAllitemsMapper.truncatetable(tablename_cang);
        }

        String tablename_ceng = "cang_ceng_" + damsConstruction.getPid() + "_" + damsConstruction.getEngcode();
        int ishave = cangAllitemsMapper.checktable(tablename_ceng);
        if (ishave == 0) {
            cangAllitemsMapper.createtableceng(tablename_ceng);
            cangAllitemsMapper.createindexs(tablename_ceng);
        } else {

            cangAllitemsMapper.truncatetable(tablename_ceng);
        }


        List<String> carString = new LinkedList<>();

        //2023年12月9日 10:33:47 修改存储坐标为 大图坐标系的的 平面坐标
        int x = 0;
        for (MatrixItemNews[] matrixItemNews : matrix) {
            int y = 0;
            for (MatrixItemNews matrixItemNew : matrixItemNews) {
                try {
                    if (null != matrixItemNew) {

                        List<RollingData> alld = matrixItemNew.getAlldata();
                        if (alld.size() > 0 && matrixItemNew.getRollingTimes() == alld.size()) {
                            List<RollingData> first_end = new ArrayList<>();
                            first_end.add(alld.get(alld.size() - 1));
                            for (RollingData rollingData : first_end) {
                                String vehicleID = rollingData.getVehicleID();
                                carString.add(vehicleID);
                                CangAllitems cangAllitems = new CangAllitems();
                                cangAllitems.setCarid(Long.valueOf(rollingData.getVehicleID()));
                                int xtem = (int) (rollingDataRange.getMinCoordX() + x);
                                int ytem = (int) (rollingDataRange.getMinCoordY() + y);
                                cangAllitems.setPx(Long.valueOf(xtem));
                                cangAllitems.setPy(Long.valueOf(ytem));
                                cangAllitems.setSpeed((long) (rollingData.getSpeed() * 100.0));
                                cangAllitems.setVcv((long) (rollingData.getVibrateValue() * 100.0));
                                cangAllitems.setPz((long) (rollingData.getElevation() * 100.0));
                                cangAllitems.setTimes(rollingData.getTimestamp());
                                allitemsList_cang.add(cangAllitems);


                                CangAllitems cengAllitems = new CangAllitems();
                                cengAllitems.setCarid(Long.valueOf(rollingData.getVehicleID()));
                                cengAllitems.setPx(Long.valueOf(xtem));
                                cengAllitems.setPy(Long.valueOf(ytem));
                                cengAllitems.setSpeed((long) (rollingData.getSpeed() * 100.0));
                                cengAllitems.setVcv((long) (rollingData.getVibrateValue() * 100.0));
                                cengAllitems.setPz((long) (rollingData.getElevation() * 100.0));
                                cengAllitems.setTimes(rollingData.getTimestamp());
                                allitemsList_ceng.add(cengAllitems);
                            }
                        }
                    }

                    if (allitemsList_cang.size() > 40000) {
                        System.out.println("保存1W条==" + x + ">" + y);
                        cangAllitemsMapper.inserbatch(tablename_cang, allitemsList_cang);
                        allitemsList_cang.clear();
                    }
                    if (allitemsList_ceng.size() > 40000) {
                        System.out.println("保存4W条==" + x + ">" + y);
                        cangAllitemsMapper.inserbatch(tablename_ceng, allitemsList_ceng);
                        allitemsList_ceng.clear();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                y++;
            }
            x++;
        }

        if (allitemsList_cang.size() > 0) {
            cangAllitemsMapper.inserbatch(tablename_cang, allitemsList_cang);
            allitemsList_cang.clear();
        }
        if (allitemsList_ceng.size() > 0) {
            cangAllitemsMapper.inserbatch(tablename_ceng, allitemsList_ceng);
            allitemsList_ceng.clear();
        }

        /*
          查询出当前仓位的补录区域
         */
        repairDataList = gettRepairData(tableName, cartype, damsConstruction, rollingDataRange, matrix);

        log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");
        fillpic_cengpxyz(tableName, cartype, xSize, ySize, damsConstruction, rollingDataRange, matrix, dataSizetotal, repairDataList, avgevolutiontop, result, range, carList.get(0));
        return result;
    }


    public static double[] calculateBoundingBox(List<Point2D> points) {
        // 使用Java 8 Stream API来找到最小和最大的x和y值
        double minX = points.stream().mapToDouble(Point2D::getX).min().orElse(Double.MAX_VALUE);
        double minY = points.stream().mapToDouble(Point2D::getY).min().orElse(Double.MAX_VALUE);
        double maxX = points.stream().mapToDouble(Point2D::getX).max().orElse(Double.MIN_VALUE);
        double maxY = points.stream().mapToDouble(Point2D::getY).max().orElse(Double.MIN_VALUE);

        // 返回包含四个坐标的数组：[minX, minY, maxX, maxY]
        return new double[]{minX, minY, maxX, maxY};
    }

    public static List<Point2D> calculateBoundingBoxPoints(double minX, double minY, double maxX, double maxY) {
        List<Point2D> points = new ArrayList<>();
        // 左上角
        points.add(new Point2D(minX, minY));
        // 右上角
        points.add(new Point2D(maxX, minY));
        // 右下角
        points.add(new Point2D(maxX, maxY));
        // 左下角
        points.add(new Point2D(minX, maxY));
        return points;
    }

    /**
     * 通过层位id获取这个层的所有仓位数据进行数据生成。
     *
     * @param tableName
     * @param cartype
     * @return
     */
    public JSONObject getHistoryPicbyalltabledata(Integer pid, Integer ceng, Integer cartype) {

        JSONObject result = new JSONObject();
        Integer cangid = 0;
        List<String> alltab = new ArrayList<>();
        List<DamsConstruction> allcang = damsConstructionMapper.getalltabbypidandencode(pid, ceng + "");
        List<Point2D> list = new ArrayList<>();
        for (DamsConstruction damsConstruction : allcang) {
            if (0 == cangid) {
                cangid = damsConstruction.getId();
            }
            alltab.add(damsConstruction.getTablename());
            String ranges = damsConstruction.getRanges();
            list.addAll(JSONArray.parseArray(ranges, Point2D.class));

        }
        double[] boundingBox = calculateBoundingBox(list);
        double minX = boundingBox[0];
        double minY = boundingBox[1];
        double maxX = boundingBox[2];
        double maxY = boundingBox[3];
        List<Point2D> boundingBoxPoints = calculateBoundingBoxPoints(minX, minY, maxX, maxY);
        String wjranges = "[";
        for (Point2D p : boundingBoxPoints) {
            wjranges += "{\"x\":" + p.x + ",\"y\":" + p.y + "},";
        }
        while (wjranges.endsWith(",")) {
            wjranges = wjranges.substring(0, wjranges.length() - 1);
        }
        wjranges += "]";
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(cangid);
        damsConstruction.setRanges(wjranges);
        damsConstruction.setXbegin(minX);
        damsConstruction.setXend(maxX);
        damsConstruction.setYbegin(minY);
        damsConstruction.setYend(maxY);

        //工作仓 数据
        Double avgevolutiontop = 0.0;
        for (String s : alltab) {
            String tableName = GlobCache.cartableprfix[cartype] + "_" + s;
            int dataSizetotal = 0;
            //查询所有数据的平均高程 用以计算平整度：
            try {
                Double nowavg = damsConstructionMapper.getdamavgevolution(tableName);
                if (null != nowavg && nowavg > 0) {
                    avgevolutiontop = (avgevolutiontop + nowavg) / 2;
                }
            } catch (Exception e) {
                System.out.println("上一层仓位不存在。");
            }

        }


        // String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        String range = "";
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());
        //所有车
        List<Car> carList = carMapper.findCar();
        List<TRepairData> repairDataList = null;
        //判断工作仓数据是否存在
        // boolean isHave=StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        long start = System.currentTimeMillis();
        //不存在则进行
        MatrixItemNews[][] matrix = new MatrixItemNews[xSize][ySize];
        for (int i = 0; i < xSize; i++) {
            for (int j = 0; j < ySize; j++) {
                matrix[i][j] = new MatrixItemNews();
            }
        }

        // if(2 == dmstatus) {
        shorehouseRange.put(String.valueOf(cangid), storehouseRange);
        // }
        int threadNum = CORECOUNT;
        ExecutorService exec = Executors.newFixedThreadPool(threadNum);
        // 定义一个任务集合
        List<Callable<Integer>> tasks = new LinkedList<>();
        int dataSize = 0;
        //2 获得数据库中的数据
        for (Car car : carList) {
            if (!car.getType().equals(cartype)) {
                continue;
            }
            List<RollingData> rollingDataList = new ArrayList<>();
            try {
                for (DamsConstruction construction : allcang) {
                    String tableName = GlobCache.cartableprfix[cartype] + "_" + construction.getTablename();
                    rollingDataList.addAll(rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString()));
                }
            } catch (Exception e) {
                System.out.println("表不存在。");
            }

            // 总数据条数

            if (rollingDataList.isEmpty()) {
                continue;
            }
            dataSize += rollingDataList.size();
            System.out.println("即将处理》》" + dataSize + "条数据。。");

            //如果是摊铺 需要计算出所有数据的

            //每个线程处理的数据量
            int dataSizeEveryThread = dataSize / (threadNum);
            // 创建一个线程池


            Callable<Integer> task = null;
            List<RollingData> cutList = null;

            // 确定每条线程的数据
            for (int i = 0; i < threadNum; i++) {

                try {
                    if (i == threadNum - 1) {
                        int index = Math.max(dataSizeEveryThread * i - 1, 0);
                        cutList = rollingDataList.subList(index - 1, dataSize);
                    } else {
                        if (i == 0) {
                            cutList = rollingDataList.subList(0, dataSizeEveryThread * (i + 1));
                        } else {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i - 2, dataSizeEveryThread * (i + 1));
                        }
                    }
                    final List<RollingData> listRollingData = cutList;
                    task = new CalculateCangHistorypicByCeng();
                    ((CalculateCangHistorypicByCeng) task).setRollingDataRange(rollingDataRange);
                    ((CalculateCangHistorypicByCeng) task).setRollingDataList(listRollingData);
                    ((CalculateCangHistorypicByCeng) task).setxNum(xSize);
                    ((CalculateCangHistorypicByCeng) task).setyNum(ySize);
                    ((CalculateCangHistorypicByCeng) task).setCartype(cartype);
                    ((CalculateCangHistorypicByCeng) task).setTempmatrix(matrix);
                    // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                    tasks.add(task);
                } catch (Exception e) {
                    System.out.println("仓位无数据");
                }
            }
            try {
                List<Future<Integer>> results = exec.invokeAll(tasks);
                for (Future<Integer> future : results) {
                    future.get();
                }
                // 关闭线程池
                log.info("线程任务执行结束");
            } catch (Exception e) {
                e.printStackTrace();
            }
            tasks.clear();
        }
        exec.shutdown();

        System.out.println(xSize + ">" + ySize);
        List<CangAllitems> allitemsList = new ArrayList<>();
        CangAllitems cangAllitems = new CangAllitems();
        String tablename = "cang_allitems_" + damsConstruction.getTablename();
        int ishave = cangAllitemsMapper.checktable(tablename);
        if (ishave == 0) {
            cangAllitemsMapper.createtable(tablename);
            cangAllitemsMapper.createindexs(tablename);
        } else {
            cangAllitemsMapper.truncatetable(tablename);
        }

        //2023年12月9日 10:33:47 修改存储坐标为 大图坐标系的的 平面坐标
        int x = 0;
        for (MatrixItemNews[] matrixItemNews : matrix) {
            int y = 0;
            for (MatrixItemNews matrixItemNew : matrixItemNews) {
                try {
                    if (null != matrixItemNew) {
                        List<RollingData> alld = matrixItemNew.getAlldata();
                        if (null != alld && alld.size() > 0) {
                            cangAllitems = new CangAllitems();
                            RollingData rollingData = alld.get(alld.size() - 1);
                            //for (RollingData rollingData : alld) {
                            cangAllitems.setCarid(Long.valueOf(rollingData.getVehicleID()));
                            int xtem = (int) (rollingDataRange.getMinCoordX() + x);
                            int ytem = (int) (rollingDataRange.getMinCoordY() + y);
                            cangAllitems.setPx(Long.valueOf(xtem));
                            cangAllitems.setPy(Long.valueOf(ytem));
                            cangAllitems.setSpeed((long) (rollingData.getSpeed() * 100.0));
                            cangAllitems.setVcv((long) (rollingData.getVibrateValue() * 100.0));
                            cangAllitems.setPz((long) (rollingData.getElevation() * 100.0));
                            cangAllitems.setTimes(rollingData.getTimestamp());
                            allitemsList.add(cangAllitems);
                            // }
                        }

                    }

                    if (allitemsList.size() > 10000) {
                        //  System.out.println("保存1W条==" + x + ">" + y);
                        //   cangAllitemsMapper.inserbatch(tablename, allitemsList);
                        allitemsList.clear();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                y++;
            }
            x++;
        }
        if (allitemsList.size() > 0) {
            // cangAllitemsMapper.inserbatch(tablename, allitemsList);
            allitemsList.clear();
        }

        /*
          查询出当前仓位的补录区域
         */
        //repairDataList = gettRepairData(tableName, cartype, damsConstruction, rollingDataRange, matrix);

        log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");
        fillpic(tablename, cartype, xSize, ySize, damsConstruction, rollingDataRange, matrix, dataSize, repairDataList, avgevolutiontop, result, range);
        return result;
    }


    private void fillpic_cengpxyz(String tableName, Integer cartype, int xSize, int ySize, DamsConstruction damsConstruction, RollingDataRange rollingDataRange, MatrixItemNews[][] matrix, int dataSizetotal, List<TRepairData> repairDataList, Double avgevolutiontop, JSONObject result, String range, Car car) {
        long g2time1 = System.currentTimeMillis();
        RollingResult rollingResult = new RollingResult();
        //超限
        RollingResult rollingResultSpeed = new RollingResult();
        //动静碾压
        RollingResult rollingResultVibrationD = new RollingResult();
        RollingResult rollingResultyashi = new RollingResult();
        RollingResult rollingResultVibrationJ = new RollingResult();
        RollingResult rollingResultEvolution = new RollingResult();
        RollingResult rollingResultEvolution2 = new RollingResult();
        RollingResult rollingResultHoudu = new RollingResult();
        int count0 = 0;
        int count0Speed = 0;
        int countyashi = 0;
        String bsae64_string = "";
        String bsae64_string_speed = "";
        String bsae64_string_yashi = "";
        String bsae64_string_vibrationD = "";
        String bsae64_string_vibrationJ = "";
        String bsae64_string_evolution = "";
        String bsae64_string_evolution2 = "";
        String bsae64_string_houdu = "";
        Map<Integer, Color> colorMap = getColorMap(GlobCache.carcoloconfigtype[cartype].longValue());

        TColorConfig vo = new TColorConfig();
        vo.setType(3L);//超限
        List<TColorConfig> colorConfigs = colorConfigMapper.select(vo);
        vo.setType(1L);//超限
        List<TColorConfig> colorConfigs1 = colorConfigMapper.select(vo);
        vo.setType(6L);//动静碾压
        List<TColorConfig> colorConfigs6 = colorConfigMapper.select(vo);
        vo.setType(44L);//摊铺平整度颜色
        List<TColorConfig> colorConfigs44 = colorConfigMapper.select(vo);
        vo.setType(45L);//摊铺厚度度颜色
        List<TColorConfig> colorConfigs45 = colorConfigMapper.select(vo);
        Map<Long, MatrixItemNews> reportdetailsmap = new HashMap<>();
        List<TDamsconstrctionReportDetail> allreportpoint = new LinkedList<>();
        // 同步代码块
        synchronized (obj1) {
            //绘制图片
            //得到图片缓冲区
            BufferedImage bi = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //超限次数
            BufferedImage biSpeed = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biyashi = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //动静碾压
            BufferedImage biVibrationD = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biVibrationJ = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biEvolution = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biEvolution2 = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150

            BufferedImage biHoudu = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150

            //得到它的绘制环境(这张图片的笔)
            Graphics2D g2 = (Graphics2D) bi.getGraphics();
            //超限次数
            Graphics2D g2Speed = (Graphics2D) biSpeed.getGraphics();
            Graphics2D g2yashi = (Graphics2D) biyashi.getGraphics();
            Graphics2D g2Houdu = (Graphics2D) biHoudu.getGraphics();
            //动碾压
            Graphics2D g2VibrationD = (Graphics2D) biVibrationD.getGraphics();
            //静碾压
            Graphics2D g2VibrationJ = (Graphics2D) biVibrationJ.getGraphics();
            //平整度
            Graphics2D g2Evolution = (Graphics2D) biEvolution.getGraphics();
            Graphics2D g2Evolution2 = (Graphics2D) biEvolution2.getGraphics();
            List<Coordinate> list = JSONArray.parseArray(damsConstruction.getRanges(), Coordinate.class);
            Coordinate[] pointList = new Coordinate[list.size() + 1];  //最重要，不能遗漏，预先申请好数组空间
            list.toArray(pointList);
            pointList[list.size()] = pointList[0];
            //记录遍数
            GeometryFactory gf = new GeometryFactory();
            Geometry edgePoly = gf.createPolygon(pointList);
            long startTime = System.currentTimeMillis();

            //查询该区域是否存在试验点数据。
            TDamsconstructionReport treport = new TDamsconstructionReport();
            treport.setDamgid(Long.valueOf(damsConstruction.getId()));
            List<TDamsconstructionReport> allreport = tDamsconstructionReportService.selectTDamsconstructionReportList(treport);

            if (allreport.size() > 0) {
                for (TDamsconstructionReport tDamsconstructionReport : allreport) {
                    tDamsconstructionReport = tDamsconstructionReportService.selectTDamsconstructionReportByGid(tDamsconstructionReport.getGid());
                    List<TDamsconstrctionReportDetail> details = tDamsconstructionReport.gettDamsconstrctionReportDetailList();
                    allreportpoint.addAll(details);
                }
            }

            for (TDamsconstrctionReportDetail next : allreportpoint) {
                List<Coordinate> rlist = JSONArray.parseArray(next.getRanges(), Coordinate.class);
                if (rlist.size() == 1) {
                    Coordinate tempc = rlist.get(0);
                    double xxd = tempc.getOrdinate(0) - rollingDataRange.getMinCoordX();
                    int portx = (int) xxd;
                    double yyd = tempc.getOrdinate(1) - rollingDataRange.getMinCoordY();
                    int porty = (int) yyd;
                    if (null != matrix[portx][porty]) {
                        reportdetailsmap.put(next.getGid(), matrix[portx][porty]);
                    }
                }
            }

            System.out.println("数据保存完成。");
            //如果是摊铺推平 需要先计算出所有碾压区域的最后一个后点高程的平均值，然后通过与平均值的差值展示显示平整度
            //   MatrixItemNews[][] items = matrix;
            Double begin_evolution = damsConstruction.getGaochengact();
            Double target_houdu = damsConstruction.getCenggao();
            Double current_avg_gaocheng = null;
            double normalHoudu = 0;
            int pid = damsConstruction.getPid();
            if (pid == 11 || pid == 12) {
                normalHoudu = 120d;
            } else if (pid == 13 || pid == 14) {
                normalHoudu = 40d;

            } else if (pid == 15) {
                normalHoudu = 20d;
            }
            try {
                current_avg_gaocheng = rollingDataMapper.getavgevolution("ylj_" + damsConstruction.getTablename());
            } catch (Exception e) {
                System.out.println("当前仓不存在。");
            }

            //推平结果处理
            if (cartype == 2) {
                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            if (dataSizetotal == 0) {
                                break;
                            }
                            try {
                                MatrixItemNews m = matrix[i][j];
                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> evlist = m.getAlldata().stream().map(RollingData::getCurrentEvolution).collect(Collectors.toList());
                                    Float currentevolution2 = evlist.size() == 0 ? 0f : evlist.get(evlist.size() - 1);
                                    float tphoudu = 100.0f * (currentevolution2 - begin_evolution.floatValue()) - target_houdu.floatValue();
                                    if (tphoudu != 0) {
                                        tphoudu = 8;
                                    }
                                    g2Evolution.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                    calculateRollingEvolution(tphoudu, rollingResult, colorConfigs44);
                                    g2Evolution.fillRect(i, j, 2, 2);
                                }


                            } catch (Exception e) {
                                log.error("出现错误。");
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                e.printStackTrace(new PrintStream(baos));
                                String exception = baos.toString();
                                log.error(exception);
                            }
                        }
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    ImageIO.write(biEvolution, "PNG", baos);
                    byte[] bytes = baos.toByteArray();//转换成字节
                    bsae64_string_evolution = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                    baos.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }
            //碾压结果处理
            else if (cartype == 1) {
                //2023年12月9日 10:41:11 改为通过上一层平均高层-与本层平均高程之差做差
                Integer encode = Integer.valueOf(damsConstruction.getEngcode());
                double before_avg_gaocheng = damsConstruction.getGaocheng();
                Integer ifhavebeforeceng = cangAllitemsMapper.checktable("cang_ceng_" + damsConstruction.getPid() + "_" + (encode - 1));
                List<CangAllitems> pxyzlist = new ArrayList<>();
                Map<String, Long> pxyzmap = new HashMap<>();
                if (ifhavebeforeceng == 1) {
                    pxyzlist = cangAllitemsMapper.selecengpxyzbyceng(damsConstruction.getPid(), encode - 1, encode);
                    if (pxyzlist.size() > 0)
                        pxyzmap = pxyzlist.stream().collect(Collectors.toMap(item -> item.getPx() + "," + item.getPy(), CangAllitems::getPz));
                }


                float tphoudu2All = 0;
                int counttphoudu2All = 0;


                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        Double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        Double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);

                        if (p1) {
                            MatrixItemNews m = matrix[i][j];
                            Integer rollingTimes = m.getRollingTimes();
                            if (rollingTimes == 0) {
                                continue;
                            }
                            count0++;
                            count0Speed++;

                            if (dataSizetotal == 0) {
                                assert repairDataList != null;
                                if (repairDataList.size() == 0) {
                                    break;
                                }
                            }

                            //合格-不合格
                            Color yashilv = new Color(14, 146, 14);
                            Color yashihong = new Color(246, 100, 100);
                            Color yashicolor = null;

                            if (rollingTimes >= 8) {
                                yashicolor = yashilv;
                                countyashi++;
                            } else if (null != rollingTimes && rollingTimes > 0 && null != current_avg_gaocheng) {
                                yashicolor = yashihong;
                            } else {
                                countyashi++;
                                yashicolor = new Color(255, 255, 255, 0);
                            }

                            g2.setColor(getColorByCount2(rollingTimes, colorMap));
                            calculateRollingtimes(rollingTimes, rollingResult);
                            g2.fillRect(i, j, 2, 2);

                            g2yashi.setColor(yashicolor);
                            rollingResultyashi.setTime8(rollingResultyashi.getTime8() + 1);
                            g2yashi.fillRect(i, j, 2, 2);


                            // TODO: 2021/10/21  超速次数
                            //获得速度集合
                            try {
                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> speeds = m.getAlldata().stream().map(RollingData::getSpeed).collect(Collectors.toList());
                                    if (speeds != null) {
                                        g2Speed.setColor(getColorByCountSpeed(StringUtils.isNotEmpty(speeds) ? speeds.get(speeds.size() - 1) : new Float(-1), colorConfigs));
                                        calculateRollingSpeed(StringUtils.isNotEmpty(speeds) ? speeds.get(speeds.size() - 1) : new Float(-1), rollingResultSpeed, colorConfigs);
                                        g2Speed.fillRect(i, j, 2, 2);
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            //Vibration 动静碾压
                            try {
                                if (null != m && m.getAlldata().size() > 0) {
                                    //LinkedList<Double> vibrations = items2.getVibrateValueList();
                                    Long vib = m.getAlldata().stream().filter(rollingData -> rollingData.getVibrateValue() > 50).count();
                                    Integer notvib = Math.toIntExact(m.getAlldata().size() - vib + 1);
                                    Integer Dtarget = damsConstruction.getHeightIndex();
                                    if (null != Dtarget) {
                                        if (Dtarget.intValue() == damsConstruction.getFrequency().intValue()) {
                                            notvib = 0;
                                        }
                                    }

                                    // BigDecimal mi = new BigDecimal(vib*1.0/(notvib+vib)*100).setScale(2,RoundingMode.HALF_DOWN);
                                    //calculateRollingVibrate(notvib.doubleValue(), rollingResultVibration, colorConfigs1);
                                    calculateRollingDJ(notvib.doubleValue(), rollingResultVibrationJ, colorConfigs1);
                                    g2VibrationJ.setColor(getColorByCountVibrate(notvib.doubleValue(), colorConfigs1));
                                    g2VibrationJ.fillRect(i, j, 2, 2);

                                    calculateRollingDJ(vib.doubleValue(), rollingResultVibrationD, colorConfigs1);
                                    g2VibrationD.setColor(getColorByCountVibrate(vib.doubleValue(), colorConfigs1));
                                    g2VibrationD.fillRect(i, j, 2, 2);


                                }
                            } catch (Exception e) {
                                log.error("出现错误。");
                                e.printStackTrace();
                            }
                            //压实平整度、厚度
                            try {

                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> evlist = m.getAlldata().stream().map(RollingData::getElevation).collect(Collectors.toList());
                                    //  Double avgev  =m.getAlldata().stream().map(RollingData::getElevation).collect(Collectors.averagingDouble(Float::doubleValue)).doubleValue();
                                    Float currentevolution2 = evlist.size() == 0 ? 0f : evlist.get(evlist.size() - 1);
                                    //平整度
                                    float tphoudu = 0;
                                    if (null == avgevolutiontop) {
                                        tphoudu = 5.0f;
                                    } else {
                                        tphoudu = 100.0f * (currentevolution2 - avgevolutiontop.floatValue());
                                    }
                                    float tphoudu2 = 0.0f;
                                    if (null == current_avg_gaocheng || 0 == current_avg_gaocheng) {
                                        tphoudu2 = (float) damsConstruction.getHoudu();
                                    }
                                    //厚度 2023年12月9日 10:57:46
                                    // float tphoudu2 = 100.0f * (currentevolution2 - begin_evolution.floatValue());
                                    else {
                                        if (pxyzlist.size() > 0) {//因为存入的时候已经是*100  这里得到的就是CM
                                            Long pz = pxyzmap.get(xTmp.intValue() + "," + yTmp.intValue());
                                            if (null != pz) {
                                                tphoudu2 = pz.floatValue();
                                            } else {
                                                tphoudu2 = (float) (current_avg_gaocheng - before_avg_gaocheng) * 100;
                                            }
                                        } else {
                                            tphoudu2 = (float) (current_avg_gaocheng - before_avg_gaocheng) * 100;
                                        }
                                    }


                                    //厚度  合格不合格
                                    Color houduGreen = new Color(14, 146, 14);
                                    Color houduRed = new Color(255, 0, 0);
                                    Color houduColor = null;
                                    //改变比较逻辑  测试


                                    if (tphoudu2 <= normalHoudu) {
                                        houduColor = houduGreen;
                                        countTime0++;
                                        rollingResultHoudu.setTime0(countTime0);
                                    } else {
                                        houduColor = houduRed;
                                        countTime1++;
                                        rollingResultHoudu.setTime1(countTime1);

                                    }

                                    g2Houdu.setColor(houduColor);
                                    g2Houdu.fillRect(i, j, 2, 2);


                                    //平均厚度
                                    tphoudu2All += tphoudu2;
                                    counttphoudu2All++;


                                    g2Evolution.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                    calculateRollingEvolution(tphoudu, rollingResultEvolution, colorConfigs44);
                                    g2Evolution.fillRect(i, j, 2, 2);

                                    g2Evolution2.setColor(getColorByCountEvolution(tphoudu2, colorConfigs45));
                                    calculateRollingEvolution(tphoudu2, rollingResultEvolution2, colorConfigs45);
                                    g2Evolution2.fillRect(i, j, 2, 2);
                                }


                            } catch (Exception e) {
                                log.error("出现错误。");
                                e.printStackTrace();
                            }

                        }
                    }
                }

                //在这进行厚度预警
                float avergetphoudu = tphoudu2All / counttphoudu2All;

                //上层平均高程

                TReportSave tReportSave = tReportSaveService.seletbyDamAndType(Long.valueOf(String.valueOf(damsConstruction.getId())), 1l);
                String base64 = tReportSave.getBase64();
                JSONObject jsonObject = JSONObject.parseObject(base64);
                double beforegaocheng = 0.0;
                if (null != jsonObject && null != jsonObject.getDouble("beforegaocheng")) {
                    beforegaocheng = jsonObject.getDouble("beforegaocheng");
                }


                //设计厚度
                double designHoudu = damsConstruction.getHoudu();

                if (avergetphoudu > normalHoudu) {
                    System.out.println("高于目标厚度值");
                    System.out.println("当前厚度：" + avergetphoudu + "短信中显示厚度（仓的设计厚度）：" + designHoudu);
                    //触发厚度预警
                    houWarning(avergetphoudu, designHoudu, damsConstruction.getId(), damsConstruction.getTitle(), car, beforegaocheng, current_avg_gaocheng);
                }

                System.out.println("上一层平均高程" + begin_evolution);
                System.out.println("本层平均高程" + current_avg_gaocheng);
                System.out.println("【平均厚度为】" + avergetphoudu);


                // GlobCache.encode_gc.put(codekey,alllastgc);

                System.out.println("矩形区域内一共有点" + count0 + "个");
                //count0表示所有属于当前单元工程的轨迹点 time0表示在计算完遍数后，该单元工程内没有碾压的数据
                int time0 = count0 - rollingResult.getTime1() - rollingResult.getTime2() - rollingResult.getTime3() - rollingResult.getTime4() - rollingResult.getTime5() - rollingResult.getTime6() - rollingResult.getTime7() - rollingResult.getTime8()
                        - rollingResult.getTime9() - rollingResult.getTime10() - rollingResult.getTime11() - rollingResult.getTime11Up();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0 <= 0) {
                    time0 = 0;
                }
                if (null == current_avg_gaocheng) {
                    countyashi = count0;
                    rollingResultyashi.setTime0(0);
                    rollingResult.setTime0(0);
                } else {
                    int timeyashi0 = count0 - countyashi;
                    rollingResultyashi.setTime0(timeyashi0);
                    rollingResult.setTime0(time0);
                }

                //

                //速度超限
                int time0Speed = count0Speed - rollingResultSpeed.getTime1() - rollingResultSpeed.getTime2();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0Speed <= 0) {
                    time0Speed = 0;
                }
                rollingResultSpeed.setTime0(time0Speed);

                long endTime = System.currentTimeMillis();    //获取结束时间
                log.info("程序运行时间：" + (endTime - startTime) + "ms");    //输出程序运行时间
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                //超限
                ByteArrayOutputStream baosSpeed = new ByteArrayOutputStream();
                ByteArrayOutputStream baosyashi = new ByteArrayOutputStream();
                //动碾压
                ByteArrayOutputStream baosVibrationD = new ByteArrayOutputStream();
                //静碾压
                ByteArrayOutputStream baosVibrationJ = new ByteArrayOutputStream();
                //碾压平整度
                ByteArrayOutputStream baoev = new ByteArrayOutputStream();
                //碾压厚度
                ByteArrayOutputStream baoev2 = new ByteArrayOutputStream();
                //        bi = (BufferedImage) ImgRotate.imageMisro(bi,0);

                ByteArrayOutputStream baoHoudu = new ByteArrayOutputStream();

                try {
                    //遍数
                    ImageIO.write(bi, "PNG", baos);
                    byte[] bytes = baos.toByteArray();//转换成字节
                    bsae64_string = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                    baos.close();
                    try {
                        //超限
                        ImageIO.write(biSpeed, "PNG", baosSpeed);
                        byte[] bytesSpeed = baosSpeed.toByteArray();//转换成字节
                        bsae64_string_speed = "data:image/png;base64," + Base64.encodeBase64String(bytesSpeed);
                        baosSpeed.close();

                        ImageIO.write(biyashi, "PNG", baosyashi);
                        byte[] byteyashi = baosyashi.toByteArray();//转换成字节
                        bsae64_string_yashi = "data:image/png;base64," + Base64.encodeBase64String(byteyashi);
                        baosyashi.close();


                        //动静碾压
                        ImageIO.write(biVibrationD, "PNG", baosVibrationD);
                        byte[] bytesVibrationD = baosVibrationD.toByteArray();//转换成字节
                        bsae64_string_vibrationD = "data:image/png;base64," + Base64.encodeBase64String(bytesVibrationD);
                        baosVibrationD.close();

                        //动静碾压
                        ImageIO.write(biVibrationJ, "PNG", baosVibrationJ);
                        byte[] bytesVibrationJ = baosVibrationJ.toByteArray();//转换成字节
                        bsae64_string_vibrationJ = "data:image/png;base64," + Base64.encodeBase64String(bytesVibrationJ);
                        baosVibrationJ.close();

                        //平整度
                        ImageIO.write(biEvolution, "PNG", baoev);
                        byte[] byteev = baoev.toByteArray();//转换成字节
                        bsae64_string_evolution = "data:image/png;base64," + Base64.encodeBase64String(byteev);
                        baoev.close();
                        //碾压厚度
                        ImageIO.write(biEvolution2, "PNG", baoev2);
                        byte[] byteev2 = baoev2.toByteArray();//转换成字节
                        bsae64_string_evolution2 = "data:image/png;base64," + Base64.encodeBase64String(byteev2);
                        baoev2.close();

                        ImageIO.write(biHoudu, "PNG", baoHoudu);
                        byte[] byteHoudu = baoHoudu.toByteArray();//转换成字节
                        bsae64_string_houdu = "data:image/png;base64," + Base64.encodeBase64String(byteHoudu);
                        baoHoudu.close();

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }


                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        long g2time2 = System.currentTimeMillis();
        System.out.println("生成图片耗时：" + (g2time2 - g2time1) / 1000);
        StoreHouseMap.getStoreHouses2RollingData().remove(tableName);

        ProjCoordinate projCoordinate1 = new ProjCoordinate(rollingDataRange.getMinCoordY(), rollingDataRange.getMinCoordX(), 10);
        ProjCoordinate projCoordinate2 = new ProjCoordinate(rollingDataRange.getMaxCoordY(), rollingDataRange.getMaxCoordX(), 10);

        result.put("rollingResult", rollingResult);
        result.put("rollingResultSpeed", rollingResultSpeed);
        result.put("rollingResultyashi", rollingResultyashi);
        result.put("rollingResultVibrationD", rollingResultVibrationD);
        // result.put("rollingResultVibrationD", rollingResult);
        result.put("rollingResultVibrationJ", rollingResultVibrationJ);
        result.put("rollingResultEvolution", rollingResultEvolution);
        result.put("rollingResultEvolution2", rollingResultEvolution2);
        result.put("base64", bsae64_string);
        result.put("base64Speed", bsae64_string_speed);
        result.put("base64yashi", bsae64_string_yashi);
        //  result.put("base64VibrationD", bsae64_string_vibrationD);
        result.put("base64VibrationD", bsae64_string);
        result.put("base64VibrationJ", bsae64_string_vibrationJ);
        result.put("base64Evolution", bsae64_string_evolution);
        result.put("base64Evolution2", bsae64_string_evolution2);
        result.put("rollingDataRange", rollingDataRange);
        result.put("pointLeftBottom", projCoordinate1);
        result.put("pointRightTop", projCoordinate2);
        result.put("height", damsConstruction.getGaochengact());
        result.put("cenggao", damsConstruction.getCenggao());
        result.put("range", range);
        result.put("reportdetailsmap", reportdetailsmap);
        result.put("allreportpoint", allreportpoint);
        //边界
        result.put("ranges", damsConstruction.getRanges());
        result.put("id", damsConstruction.getId());
        List<JSONObject> images = new LinkedList<>();
        result.put("images", images);

        result.put("base64Houdu", bsae64_string_houdu);
        result.put("rollingResultHoudu", rollingResultHoudu);
        // result.put("matriss", JSONObject.toJSONString(matrix));
        countTime0 = 0;
        countTime1 = 0;

    }


    //厚度预警
    public void houWarning(double currentHoudu, double normalHoudu, Integer cangid, String cangname, Car car, Double begin_evolution, Double current_avg_gaocheng) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        TSpeedWarming temp = new TSpeedWarming();
        Date d = new Date();
        long nowTime = System.currentTimeMillis();
        d.setTime(nowTime);
        temp.setCarid(Long.valueOf(car.getCarID()));
        temp.setDatatime(sdf.format(d));
        temp.setCurrentvalue(new BigDecimal(currentHoudu).setScale(2, RoundingMode.HALF_UP));
        temp.setNormalvalue(new BigDecimal(normalHoudu).setScale(2, RoundingMode.HALF_UP));
        temp.setDamgid(Long.valueOf(cangid));
        temp.setDamtitle(cangname);
        temp.setGid(UUID.randomUUID().toString().replace("-", ""));
        temp.setCreattime(DateUtils.getTime());
        temp.setFreedom5("2");
        speedmapper.insertTSpeedWarming(temp);
        List<TWarningUser> users = warningUserMapper.selectuserlist();
        String carRemark = car.getRemark();
        long lastTime = lastExecutionTime.getOrDefault(carRemark, 0L);
        if (nowTime - lastTime >= TEN_MINUTES_IN_MILLIS) {
            for (TWarningUser user : users) {
                savesendrecord(currentHoudu, normalHoudu, cangid, car, user);
                String title = damsConstructionMapper.selectDamTitleById(cangid);
                String designGaocheng = damsConstructionMapper.selectdesignGaochengById(cangid);
                String damename = damsConstructionMapper.selectDameNameByPid(cangid);
                SMSBean sm = new SMSBean();
                sm.setUsertel(user.getTel());
                sm.setUsername(user.getName());
                sm.setTime(DateUtils.getTime());
                sm.setSitename("寺桥水库工程");
                sm.setDevicename(car.getRemark());
                sm.setNormalhoudu(String.valueOf((int) normalHoudu));
                sm.setCurrenthoudu(String.format("%.2f", currentHoudu));
                sm.setTitle(title);
                sm.setDamname(damename);
                sm.setBegin_evolution(String.format("%.2f", begin_evolution));
                sm.setCurrent_avg_gaocheng(String.format("%.2f", current_avg_gaocheng));
                sm.setDesignGaocheng(designGaocheng);

                MLTSMSutils.send_houdu(sm);
            }
            lastExecutionTime.put(carRemark, nowTime);
        }
    }

    private void savesendrecord(double currentspeed, double normalspeed, Integer cangid, Car car, TWarningUser user) {
        SmsSendRecord record = new SmsSendRecord();
        record.setCarName(car.getRemark());
        record.setCurrentValue(new BigDecimal(currentspeed).setScale(2, RoundingMode.HALF_UP));
        record.setNormalValue(new BigDecimal(normalspeed).setScale(2, RoundingMode.HALF_UP));
        record.setDamGid(cangid.longValue());
        record.setSmsTel(user.getTel());
        record.setTelUser(user.getName());
        record.setCreateTime(new Date());
        record.setFreedom1("2");
        smsrecordMapper.insertSmsSendRecord(record);
    }


    /**
     * 根据生成的数据生成图片
     *
     * @param tableName
     * @param cartype
     * @param xSize
     * @param ySize
     * @param damsConstruction
     * @param rollingDataRange
     * @param matrix
     * @param dataSizetotal
     * @param repairDataList
     * @param avgevolutiontop
     * @param result
     * @param range
     */

    private void fillpic(String tableName, Integer cartype, int xSize, int ySize, DamsConstruction damsConstruction, RollingDataRange rollingDataRange, MatrixItemNews[][] matrix, int dataSizetotal, List<TRepairData> repairDataList, Double avgevolutiontop, JSONObject result, String range) {
        long g2time1 = System.currentTimeMillis();
        RollingResult rollingResult = new RollingResult();
        //超限
        RollingResult rollingResultSpeed = new RollingResult();
        //动静碾压
        RollingResult rollingResultVibrationD = new RollingResult();
        RollingResult rollingResultyashi = new RollingResult();
        RollingResult rollingResultVibrationJ = new RollingResult();
        RollingResult rollingResultEvolution = new RollingResult();
        RollingResult rollingResultEvolution2 = new RollingResult();

        RollingResult rollingResultHoudu = new RollingResult();
        int count0 = 0;
        int count0Speed = 0;
        int countyashi = 0;
        String bsae64_string = "";
        String bsae64_string_speed = "";
        String bsae64_string_yashi = "";
        String bsae64_string_vibrationD = "";
        String bsae64_string_vibrationJ = "";
        String bsae64_string_evolution = "";
        String bsae64_string_evolution2 = "";
        String bsae64_string_houdu = "";
        Map<Integer, Color> colorMap = getColorMap(GlobCache.carcoloconfigtype[cartype].longValue());

        TColorConfig vo = new TColorConfig();
        vo.setType(3L);//超限
        List<TColorConfig> colorConfigs = colorConfigMapper.select(vo);
        vo.setType(1L);//超限
        List<TColorConfig> colorConfigs1 = colorConfigMapper.select(vo);
        vo.setType(6L);//动静碾压
        List<TColorConfig> colorConfigs6 = colorConfigMapper.select(vo);
        vo.setType(44L);//摊铺平整度颜色
        List<TColorConfig> colorConfigs44 = colorConfigMapper.select(vo);
        vo.setType(45L);//摊铺厚度度颜色
        List<TColorConfig> colorConfigs45 = colorConfigMapper.select(vo);
        Map<Long, MatrixItemNews> reportdetailsmap = new HashMap<>();
        List<TDamsconstrctionReportDetail> allreportpoint = new LinkedList<>();
        // 同步代码块
        synchronized (obj1) {
            //绘制图片
            //得到图片缓冲区
            BufferedImage bi = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //超限次数
            BufferedImage biSpeed = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biyashi = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biHoudu = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150

            //动静碾压
            BufferedImage biVibrationD = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biVibrationJ = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biEvolution = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biEvolution2 = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150


            //得到它的绘制环境(这张图片的笔)
            Graphics2D g2 = (Graphics2D) bi.getGraphics();
            //超限次数
            Graphics2D g2Speed = (Graphics2D) biSpeed.getGraphics();
            Graphics2D g2yashi = (Graphics2D) biyashi.getGraphics();
            Graphics2D g2Houdu = (Graphics2D) biHoudu.getGraphics();

            //动碾压
            Graphics2D g2VibrationD = (Graphics2D) biVibrationD.getGraphics();
            //静碾压
            Graphics2D g2VibrationJ = (Graphics2D) biVibrationJ.getGraphics();
            //平整度
            Graphics2D g2Evolution = (Graphics2D) biEvolution.getGraphics();
            Graphics2D g2Evolution2 = (Graphics2D) biEvolution2.getGraphics();
            List<Coordinate> list = JSONArray.parseArray(damsConstruction.getRanges(), Coordinate.class);
            Coordinate[] pointList = new Coordinate[list.size() + 1];  //最重要，不能遗漏，预先申请好数组空间
            list.toArray(pointList);
            pointList[list.size()] = pointList[0];
            //记录遍数
            GeometryFactory gf = new GeometryFactory();
            Geometry edgePoly = gf.createPolygon(pointList);
            long startTime = System.currentTimeMillis();

            //查询该区域是否存在试验点数据。
            TDamsconstructionReport treport = new TDamsconstructionReport();
            treport.setDamgid(Long.valueOf(damsConstruction.getId()));
            List<TDamsconstructionReport> allreport = tDamsconstructionReportService.selectTDamsconstructionReportList(treport);

            if (allreport.size() > 0) {
                for (TDamsconstructionReport tDamsconstructionReport : allreport) {
                    tDamsconstructionReport = tDamsconstructionReportService.selectTDamsconstructionReportByGid(tDamsconstructionReport.getGid());
                    List<TDamsconstrctionReportDetail> details = tDamsconstructionReport.gettDamsconstrctionReportDetailList();
                    allreportpoint.addAll(details);
                }
            }

            for (TDamsconstrctionReportDetail next : allreportpoint) {
                List<Coordinate> rlist = JSONArray.parseArray(next.getRanges(), Coordinate.class);
                if (rlist.size() == 1) {
                    Coordinate tempc = rlist.get(0);
                    double xxd = tempc.getOrdinate(0) - rollingDataRange.getMinCoordX();
                    int portx = (int) xxd;
                    double yyd = tempc.getOrdinate(1) - rollingDataRange.getMinCoordY();
                    int porty = (int) yyd;
                    if (null != matrix[portx][porty]) {
                        reportdetailsmap.put(next.getGid(), matrix[portx][porty]);
                    }
                }
            }

            System.out.println("数据保存完成。");
            //如果是摊铺推平 需要先计算出所有碾压区域的最后一个后点高程的平均值，然后通过与平均值的差值展示显示平整度
            //   MatrixItemNews[][] items = matrix;
            Double begin_evolution = damsConstruction.getGaochengact();
            Double target_houdu = damsConstruction.getCenggao();
            Double current_avg_gaocheng = null;
            double normalHoudu = 0;
            int pid = damsConstruction.getPid();
            if (pid == 11 || pid == 12) {
                normalHoudu = 120d;
            } else if (pid == 13 || pid == 14) {
                normalHoudu = 40d;

            } else if (pid == 15) {
                normalHoudu = 20d;
            }
            try {
                current_avg_gaocheng = rollingDataMapper.getavgevolution("ylj_" + damsConstruction.getTablename());
            } catch (Exception e) {
                System.out.println("当前仓不存在。");
            }

            //推平结果处理
            if (cartype == 2) {
                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            if (dataSizetotal == 0) {
                                break;
                            }
                            try {
                                MatrixItemNews m = matrix[i][j];
                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> evlist = m.getAlldata().stream().map(RollingData::getCurrentEvolution).collect(Collectors.toList());
                                    Float currentevolution2 = evlist.size() == 0 ? 0f : evlist.get(evlist.size() - 1);
                                    float tphoudu = 100.0f * (currentevolution2 - begin_evolution.floatValue()) - target_houdu.floatValue();
                                    if (tphoudu != 0) {
                                        tphoudu = 8;
                                    }
                                    g2Evolution.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                    calculateRollingEvolution(tphoudu, rollingResult, colorConfigs44);
                                    g2Evolution.fillRect(i, j, 2, 2);
                                }


                            } catch (Exception e) {
                                log.error("出现错误。");
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                e.printStackTrace(new PrintStream(baos));
                                String exception = baos.toString();
                                log.error(exception);
                            }
                        }
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    ImageIO.write(biEvolution, "PNG", baos);
                    byte[] bytes = baos.toByteArray();//转换成字节
                    bsae64_string_evolution = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                    baos.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }
            //碾压结果处理
            else if (cartype == 1) {
                //2023年12月9日 10:41:11 改为通过上一层平均高层-与本层平均高程之差做差
                Integer encode = Integer.valueOf(damsConstruction.getEngcode());
                double before_avg_gaocheng = damsConstruction.getGaocheng();
                try {
                    List<DamsConstruction> allbefore = rollingDataMapper.getDamsConstructionsbyencode(encode - 1);
                    double basecha = 10;
                    String beforecang = "";

                    if (null != allbefore && allbefore.size() > 0) {
                        for (DamsConstruction construction : allbefore) {
                            double currentc = damsConstruction.getGaocheng() - construction.getGaocheng();
                            if (currentc < basecha) {
                                basecha = currentc;
                                beforecang = construction.getTablename();
                            }
                        }
                    }
                    if (!StringUtils.isEmpty(beforecang)) {
                        Double beforegaocheng = rollingDataMapper.getavgevolution("ylj_" + beforecang);
                        if (null != beforegaocheng) {
                            before_avg_gaocheng = beforegaocheng;
                        }
                    }
                } catch (Exception e) {
                    System.out.println("前一层表不存在。" + e.getLocalizedMessage());
                }


                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);

                        if (p1) {
                            MatrixItemNews m = matrix[i][j];
                            Integer rollingTimes = m.getRollingTimes();
                            if (rollingTimes == 0) {
                                continue;
                            }
                            count0++;
                            count0Speed++;

                            if (dataSizetotal == 0) {
                                assert repairDataList != null;
                                if (repairDataList.size() == 0) {
                                    break;
                                }
                            }

                            //合格-不合格
                            Color yashilv = new Color(14, 146, 14);
                            Color yashihong = new Color(246, 100, 100);
                            Color yashicolor = null;
                            if (rollingTimes >= 8) {
                                yashicolor = yashilv;
                                countyashi++;
                            } else if (null != rollingTimes && rollingTimes > 0 && null != current_avg_gaocheng) {
                                yashicolor = yashihong;
                            } else {
                                countyashi++;
                                yashicolor = new Color(255, 255, 255, 0);
                            }
                            g2.setColor(getColorByCount2(rollingTimes, colorMap));
                            calculateRollingtimes(rollingTimes, rollingResult);
                            g2.fillRect(i, j, 2, 2);
                            g2yashi.setColor(yashicolor);
                            rollingResultyashi.setTime8(rollingResultyashi.getTime8() + 1);
                            g2yashi.fillRect(i, j, 2, 2);


                            // TODO: 2021/10/21  超速次数
                            //获得速度集合
                            try {
                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> speeds = m.getAlldata().stream().map(RollingData::getSpeed).collect(Collectors.toList());
                                    if (speeds != null) {
                                        g2Speed.setColor(getColorByCountSpeed(StringUtils.isNotEmpty(speeds) ? speeds.get(speeds.size() - 1) : new Float(-1), colorConfigs));
                                        calculateRollingSpeed(StringUtils.isNotEmpty(speeds) ? speeds.get(speeds.size() - 1) : new Float(-1), rollingResultSpeed, colorConfigs);
                                        g2Speed.fillRect(i, j, 2, 2);
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            //Vibration 动静碾压
                            try {
                                if (null != m && m.getAlldata().size() > 0) {
                                    //LinkedList<Double> vibrations = items2.getVibrateValueList();
                                    Long vib = m.getAlldata().stream().filter(rollingData -> rollingData.getVibrateValue() > 50).count();
                                    Integer notvib = Math.toIntExact(m.getAlldata().size() - vib + 1);
                                    Integer Dtarget = damsConstruction.getHeightIndex();
                                    if (null != Dtarget) {
                                        if (Dtarget.intValue() == damsConstruction.getFrequency().intValue()) {
                                            notvib = 0;
                                        }
                                    }

                                    // BigDecimal mi = new BigDecimal(vib*1.0/(notvib+vib)*100).setScale(2,RoundingMode.HALF_DOWN);
                                    //calculateRollingVibrate(notvib.doubleValue(), rollingResultVibration, colorConfigs1);
                                    calculateRollingDJ(notvib.doubleValue(), rollingResultVibrationJ, colorConfigs1);
                                    g2VibrationJ.setColor(getColorByCountVibrate(notvib.doubleValue(), colorConfigs1));
                                    g2VibrationJ.fillRect(i, j, 2, 2);

                                    calculateRollingDJ(vib.doubleValue(), rollingResultVibrationD, colorConfigs1);
                                    g2VibrationD.setColor(getColorByCountVibrate(vib.doubleValue(), colorConfigs1));
                                    g2VibrationD.fillRect(i, j, 2, 2);


//                                    if (null != vibrations && vibrations.size() > 0) {
//                                        try {
//                                            for (Double vcv : vibrations) {
//                                                //todo 动静碾压
//                                                if (StringUtils.isNotNull(vcv)) {
//                                                    calculateRollingVibrate(vcv, rollingResultVibration, colorConfigs6);
//                                                }
//                                            }
//                                            g2Vibration.setColor(getColorByCountVibrate(StringUtils.isNotEmpty(vibrations) ? Collections.max(vibrations) : new Double(-1), colorConfigs6));
//                                            g2Vibration.fillRect(i, j, 2, 2);
//                                        } catch (Exception ignored) {
//
//                                        }
//                                    }
                                }
                            } catch (Exception e) {
                                log.error("出现错误。");
                                e.printStackTrace();
                            }
                            //压实平整度、厚度
                            try {

                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> evlist = m.getAlldata().stream().map(RollingData::getElevation).collect(Collectors.toList());
                                    //  Double avgev  =m.getAlldata().stream().map(RollingData::getElevation).collect(Collectors.averagingDouble(Float::doubleValue)).doubleValue();
                                    Float currentevolution2 = evlist.size() == 0 ? 0f : evlist.get(evlist.size() - 1);
                                    //平整度
                                    float tphoudu = 0;
                                    if (null == avgevolutiontop) {
                                        tphoudu = 5.0f;
                                    } else {
                                        tphoudu = 100.0f * (currentevolution2 - avgevolutiontop.floatValue());
                                    }
                                    float tphoudu2 = 0.0f;
                                    if (null == current_avg_gaocheng || 0 == current_avg_gaocheng) {
                                        tphoudu2 = (float) damsConstruction.getHoudu();
                                    }
                                    //厚度 2023年12月9日 10:57:46
                                    // float tphoudu2 = 100.0f * (currentevolution2 - begin_evolution.floatValue());
//                                    else {
//                                        Double base = current_avg_gaocheng - before_avg_gaocheng;
//                                        // System.out.println(base);
//                                        if (base < 0.85) {
//                                            tphoudu2 = base.floatValue() * 100;
//                                        } else if (base > 1.2) {
//                                            tphoudu2 = 80.0f * base.floatValue();
//                                        } else {
//                                            tphoudu2 = 90.0f * base.floatValue();
//                                        }
//                                    }
                                    //厚度 合格 不合格
                                    Color houduGreen = new Color(14, 146, 14);
                                    Color houduRed = new Color(255, 0, 0);
                                    Color houduColor = null;
                                    if (tphoudu2 <= normalHoudu) {
                                        houduColor = houduGreen;
                                        countTime01++;
                                        rollingResultHoudu.setTime0(countTime01);  //合格
                                    } else {
                                        houduColor = houduRed;
                                        countTime02++;
                                        rollingResultHoudu.setTime1(countTime02);   //不合格
                                    }
                                    g2Houdu.setColor(houduColor);
                                    g2Houdu.fillRect(i, j, 2, 2);


                                    //  System.out.println(tphoudu2);

                                    //     System.out.println("当前高程："+currentevolution2+">>平均高程："+avgev+">>初始高程"+begin_evolution+"平整度："+tphoudu+"厚度："+tphoudu2);

//                                    if(tphoudu!=0){
//                                        tphoudu =8;
//                                    }
//                                    if(tphoudu2){
//
//                                    }

                                    g2Evolution.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                    calculateRollingEvolution(tphoudu, rollingResultEvolution, colorConfigs44);
                                    g2Evolution.fillRect(i, j, 2, 2);

                                    g2Evolution2.setColor(getColorByCountEvolution(tphoudu2, colorConfigs45));
                                    calculateRollingEvolution(tphoudu2, rollingResultEvolution2, colorConfigs45);
                                    g2Evolution2.fillRect(i, j, 2, 2);
                                }


                            } catch (Exception e) {
                                log.error("出现错误。");
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                e.printStackTrace(new PrintStream(baos));
                                String exception = baos.toString();
                                log.error(exception);
                            }

                        }
                    }
                }
                countTime01 = 0;
                countTime02 = 0;

                // GlobCache.encode_gc.put(codekey,alllastgc);

                System.out.println("矩形区域内一共有点" + count0 + "个");
                //count0表示所有属于当前单元工程的轨迹点 time0表示在计算完遍数后，该单元工程内没有碾压的数据
                int time0 = count0 - rollingResult.getTime1() - rollingResult.getTime2() - rollingResult.getTime3() - rollingResult.getTime4() - rollingResult.getTime5() - rollingResult.getTime6() - rollingResult.getTime7() - rollingResult.getTime8()
                        - rollingResult.getTime9() - rollingResult.getTime10() - rollingResult.getTime11() - rollingResult.getTime11Up();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0 <= 0) {
                    time0 = 0;
                }
                if (null == current_avg_gaocheng) {
                    countyashi = count0;
                    rollingResultyashi.setTime0(0);
                    rollingResult.setTime0(0);
                } else {
                    int timeyashi0 = count0 - countyashi;
                    rollingResultyashi.setTime0(timeyashi0);
                    rollingResult.setTime0(time0);
                }

                //

                //速度超限
                int time0Speed = count0Speed - rollingResultSpeed.getTime1() - rollingResultSpeed.getTime2();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0Speed <= 0) {
                    time0Speed = 0;
                }
                rollingResultSpeed.setTime0(time0Speed);

                long endTime = System.currentTimeMillis();    //获取结束时间
                log.info("程序运行时间：" + (endTime - startTime) + "ms");    //输出程序运行时间
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                //超限
                ByteArrayOutputStream baosSpeed = new ByteArrayOutputStream();
                ByteArrayOutputStream baosyashi = new ByteArrayOutputStream();
                //动碾压
                ByteArrayOutputStream baosVibrationD = new ByteArrayOutputStream();
                //静碾压
                ByteArrayOutputStream baosVibrationJ = new ByteArrayOutputStream();
                //碾压平整度
                ByteArrayOutputStream baoev = new ByteArrayOutputStream();
                //碾压厚度
                ByteArrayOutputStream baoev2 = new ByteArrayOutputStream();


                ByteArrayOutputStream baoHoudu = new ByteArrayOutputStream();

                //        bi = (BufferedImage) ImgRotate.imageMisro(bi,0);
                try {
                    //遍数
                    ImageIO.write(bi, "PNG", baos);
                    byte[] bytes = baos.toByteArray();//转换成字节
                    bsae64_string = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                    baos.close();
                    try {
                        //超限
                        ImageIO.write(biSpeed, "PNG", baosSpeed);
                        byte[] bytesSpeed = baosSpeed.toByteArray();//转换成字节
                        bsae64_string_speed = "data:image/png;base64," + Base64.encodeBase64String(bytesSpeed);
                        baosSpeed.close();

                        ImageIO.write(biyashi, "PNG", baosyashi);
                        byte[] byteyashi = baosyashi.toByteArray();//转换成字节
                        bsae64_string_yashi = "data:image/png;base64," + Base64.encodeBase64String(byteyashi);
                        baosyashi.close();


                        //动静碾压
                        ImageIO.write(biVibrationD, "PNG", baosVibrationD);
                        byte[] bytesVibrationD = baosVibrationD.toByteArray();//转换成字节
                        bsae64_string_vibrationD = "data:image/png;base64," + Base64.encodeBase64String(bytesVibrationD);
                        baosVibrationD.close();

                        //动静碾压
                        ImageIO.write(biVibrationJ, "PNG", baosVibrationJ);
                        byte[] bytesVibrationJ = baosVibrationJ.toByteArray();//转换成字节
                        bsae64_string_vibrationJ = "data:image/png;base64," + Base64.encodeBase64String(bytesVibrationJ);
                        baosVibrationJ.close();

                        //平整度
                        ImageIO.write(biEvolution, "PNG", baoev);
                        byte[] byteev = baoev.toByteArray();//转换成字节
                        bsae64_string_evolution = "data:image/png;base64," + Base64.encodeBase64String(byteev);
                        baoev.close();
                        //碾压厚度
                        ImageIO.write(biEvolution2, "PNG", baoev2);
                        byte[] byteev2 = baoev2.toByteArray();//转换成字节
                        bsae64_string_evolution2 = "data:image/png;base64," + Base64.encodeBase64String(byteev2);
                        baoev2.close();

                        ImageIO.write(biHoudu, "PNG", baoHoudu);
                        byte[] byteHoudu = baoHoudu.toByteArray();//转换成字节
                        bsae64_string_houdu = "data:image/png;base64," + Base64.encodeBase64String(byteHoudu);
                        baoHoudu.close();


                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }


                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        long g2time2 = System.currentTimeMillis();
        System.out.println("生成图片耗时：" + (g2time2 - g2time1) / 1000);
        StoreHouseMap.getStoreHouses2RollingData().remove(tableName);

        ProjCoordinate projCoordinate1 = new ProjCoordinate(rollingDataRange.getMinCoordY(), rollingDataRange.getMinCoordX(), 10);
        ProjCoordinate projCoordinate2 = new ProjCoordinate(rollingDataRange.getMaxCoordY(), rollingDataRange.getMaxCoordX(), 10);

        result.put("rollingResult", rollingResult);
        result.put("rollingResultSpeed", rollingResultSpeed);
        result.put("rollingResultyashi", rollingResultyashi);
        result.put("rollingResultVibrationD", rollingResultVibrationD);
        // result.put("rollingResultVibrationD", rollingResult);
        result.put("rollingResultVibrationJ", rollingResultVibrationJ);
        result.put("rollingResultEvolution", rollingResultEvolution);
        result.put("rollingResultEvolution2", rollingResultEvolution2);
        result.put("base64", bsae64_string);
        result.put("base64Speed", bsae64_string_speed);
        result.put("base64yashi", bsae64_string_yashi);
        //  result.put("base64VibrationD", bsae64_string_vibrationD);
        result.put("base64VibrationD", bsae64_string);
        result.put("base64VibrationJ", bsae64_string_vibrationJ);
        result.put("base64Evolution", bsae64_string_evolution);
        result.put("base64Evolution2", bsae64_string_evolution2);
        result.put("rollingDataRange", rollingDataRange);
        result.put("pointLeftBottom", projCoordinate1);
        result.put("pointRightTop", projCoordinate2);
        result.put("height", damsConstruction.getGaochengact());
        result.put("cenggao", damsConstruction.getCenggao());
        result.put("range", range);
        result.put("reportdetailsmap", reportdetailsmap);
        result.put("allreportpoint", allreportpoint);
        //边界
        result.put("ranges", damsConstruction.getRanges());
        result.put("id", damsConstruction.getId());
        List<JSONObject> images = new LinkedList<>();
        result.put("images", images);
        result.put("base64Houdu", bsae64_string_houdu);
        result.put("rollingResultHoudu", rollingResultHoudu);

        // result.put("matriss", JSONObject.toJSONString(matrix));
    }

    /**
     * @param tableName
     * @param cartype
     * @return
     */
    public JSONObject getHistoryPicMultiThreadingByZhuangByTodNews_avg(String tableName, Integer cartype) {

        JSONObject result = new JSONObject();
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));


        //工作仓 数据
        tableName = GlobCache.cartableprfix[cartype] + "_" + damsConstruction.getTablename();
        int dataSizetotal = 0;
        //查询所有数据的平均高程 用以计算平整度：
        Double avgevolutiontop = damsConstructionMapper.getdamavgevolution(tableName);
        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());
        //所有车
        List<Car> carList = carMapper.findCar();
        List<TRepairData> repairDataList = null;
        //判断工作仓数据是否存在
        // boolean isHave=StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        long start = System.currentTimeMillis();
        //不存在则进行
        MatrixItemNews[][] matrix = new MatrixItemNews[xSize][ySize];
        for (int i = 0; i < xSize; i++) {
            for (int j = 0; j < ySize; j++) {
                matrix[i][j] = new MatrixItemNews();
            }
        }

        // if(2 == dmstatus) {
        shorehouseRange.put(id, storehouseRange);
        // }
        int threadNum = CORECOUNT;
        ExecutorService exec = Executors.newFixedThreadPool(threadNum);
        // 定义一个任务集合
        List<Callable<Integer>> tasks = new LinkedList<>();
        //2 获得数据库中的数据
        for (Car car : carList) {
            if (!car.getType().equals(cartype)) {
                continue;
            }
            List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString());

            // 总数据条数
            int dataSize = rollingDataList.size();
            dataSizetotal += dataSize;
            if (dataSize == 0) {
                continue;
            }
            System.out.println("即将处理》》" + dataSize + "条数据。。");

            //如果是摊铺 需要计算出所有数据的

            //每个线程处理的数据量
            int dataSizeEveryThread = dataSize / (threadNum);
            // 创建一个线程池


            Callable<Integer> task = null;
            List<RollingData> cutList = null;

            // 确定每条线程的数据
            for (int i = 0; i < threadNum; i++) {

                try {
                    if (i == threadNum - 1) {
                        int index = Math.max(dataSizeEveryThread * i - 1, 0);
                        cutList = rollingDataList.subList(index - 1, dataSize);
                    } else {
                        if (i == 0) {
                            cutList = rollingDataList.subList(0, dataSizeEveryThread * (i + 1));
                        } else {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i - 2, dataSizeEveryThread * (i + 1));
                        }
                    }
                    final List<RollingData> listRollingData = cutList;
                    task = new CalculateGridForZhuangHistoryNews();
                    ((CalculateGridForZhuangHistoryNews) task).setRollingDataRange(rollingDataRange);
                    ((CalculateGridForZhuangHistoryNews) task).setRollingDataList(listRollingData);
                    ((CalculateGridForZhuangHistoryNews) task).setTableName(tableName);
                    ((CalculateGridForZhuangHistoryNews) task).setxNum(xSize);
                    ((CalculateGridForZhuangHistoryNews) task).setyNum(ySize);
                    ((CalculateGridForZhuangHistoryNews) task).setCartype(cartype);
                    ((CalculateGridForZhuangHistoryNews) task).setTempmatrix(matrix);
                    // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                    tasks.add(task);
                } catch (Exception e) {
                    System.out.println("仓位无数据");
                }
            }
            try {
                List<Future<Integer>> results = exec.invokeAll(tasks);
                for (Future<Integer> future : results) {
                    future.get();
                }
                // 关闭线程池
                log.info("线程任务执行结束");
            } catch (Exception e) {
                e.printStackTrace();
            }
            tasks.clear();
        }
        exec.shutdown();

        System.out.println(xSize + ">" + ySize);
        List<CangAllitems> allitemsList = new ArrayList<>();
        CangAllitems cangAllitems = new CangAllitems();
        String tablename = "cang_allitems_" + damsConstruction.getTablename();
        int ishave = cangAllitemsMapper.checktable(tablename);
        if (ishave == 0) {
            cangAllitemsMapper.createtable(tablename);
            cangAllitemsMapper.createindexs(tablename);
        } else {
            cangAllitemsMapper.truncatetable(tablename);
        }

        int x = 0;
        for (MatrixItemNews[] matrixItemNews : matrix) {
            int y = 0;
            for (MatrixItemNews matrixItemNew : matrixItemNews) {
                try {
                    if (null != matrixItemNew) {
                        List<RollingData> alld = matrixItemNew.getAlldata();
                        if (null != alld && alld.size() > 0) {
                            cangAllitems = new CangAllitems();
                            RollingData rollingData = alld.get(alld.size() - 1);
                            //for (RollingData rollingData : alld) {
                            cangAllitems.setCarid(Long.valueOf(rollingData.getVehicleID()));
                            cangAllitems.setPx(Long.valueOf(x));
                            cangAllitems.setPy(Long.valueOf(y));
                            cangAllitems.setSpeed((long) (rollingData.getSpeed() * 100.0));
                            cangAllitems.setVcv((long) (rollingData.getVibrateValue() * 100.0));
                            cangAllitems.setPz((long) (rollingData.getElevation() * 100.0));
                            cangAllitems.setTimes(rollingData.getTimestamp());
                            allitemsList.add(cangAllitems);
                            // }
                        }

                    }

                    if (allitemsList.size() > 10000) {
                        System.out.println("保存1W条==" + x + ">" + y);
                        cangAllitemsMapper.inserbatch(tablename, allitemsList);
                        allitemsList.clear();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                y++;
            }
            x++;
        }
        if (allitemsList.size() > 0) {
            cangAllitemsMapper.inserbatch(tablename, allitemsList);
            allitemsList.clear();
        }



        /*
          查询出当前仓位的补录区域
         */
        repairDataList = gettRepairData(tableName, cartype, damsConstruction, rollingDataRange, matrix);

        log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");
        long g2time1 = System.currentTimeMillis();
        RollingResult rollingResult = new RollingResult();
        //超限
        RollingResult rollingResultSpeed = new RollingResult();
        //动静碾压
        RollingResult rollingResultVibrationD = new RollingResult();
        RollingResult rollingResultVibrationJ = new RollingResult();
        RollingResult rollingResultEvolution = new RollingResult();
        RollingResult rollingResultEvolution2 = new RollingResult();
        int count0 = 0;
        int count0Speed = 0;

        String bsae64_string = "";
        String bsae64_string_speed = "";
        String bsae64_string_vibrationD = "";
        String bsae64_string_vibrationJ = "";
        String bsae64_string_evolution = "";
        String bsae64_string_evolution2 = "";
        Map<Integer, Color> colorMap = getColorMap(GlobCache.carcoloconfigtype[cartype].longValue());

        TColorConfig vo = new TColorConfig();
        vo.setType(3L);//超限
        List<TColorConfig> colorConfigs = colorConfigMapper.select(vo);
        vo.setType(1L);//超限
        List<TColorConfig> colorConfigs1 = colorConfigMapper.select(vo);
        vo.setType(6L);//动静碾压
        List<TColorConfig> colorConfigs6 = colorConfigMapper.select(vo);
        vo.setType(44L);//摊铺平整度颜色
        List<TColorConfig> colorConfigs44 = colorConfigMapper.select(vo);
        vo.setType(45L);//摊铺厚度度颜色
        List<TColorConfig> colorConfigs45 = colorConfigMapper.select(vo);
        Map<Long, MatrixItemNews> reportdetailsmap = new HashMap<>();
        List<TDamsconstrctionReportDetail> allreportpoint = new LinkedList<>();
        synchronized (obj1) {// 同步代码块
            //绘制图片
            //得到图片缓冲区
            BufferedImage bi = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //超限次数
            BufferedImage biSpeed = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //动静碾压
            BufferedImage biVibrationD = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biVibrationJ = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biEvolution = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            BufferedImage biEvolution2 = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //得到它的绘制环境(这张图片的笔)
            Graphics2D g2 = (Graphics2D) bi.getGraphics();
            //超限次数
            Graphics2D g2Speed = (Graphics2D) biSpeed.getGraphics();
            //动碾压
            Graphics2D g2VibrationD = (Graphics2D) biVibrationD.getGraphics();
            //静碾压
            Graphics2D g2VibrationJ = (Graphics2D) biVibrationJ.getGraphics();
            //平整度
            Graphics2D g2Evolution = (Graphics2D) biEvolution.getGraphics();
            Graphics2D g2Evolution2 = (Graphics2D) biEvolution2.getGraphics();
            List<Coordinate> list = JSONArray.parseArray(damsConstruction.getRanges(), Coordinate.class);
            Coordinate[] pointList = new Coordinate[list.size() + 1];  //最重要，不能遗漏，预先申请好数组空间
            list.toArray(pointList);
            pointList[list.size()] = pointList[0];
            //记录遍数
            GeometryFactory gf = new GeometryFactory();
            Geometry edgePoly = gf.createPolygon(pointList);
            long startTime = System.currentTimeMillis();

            //查询该区域是否存在试验点数据。
            TDamsconstructionReport treport = new TDamsconstructionReport();
            treport.setDamgid(Long.valueOf(damsConstruction.getId()));
            List<TDamsconstructionReport> allreport = tDamsconstructionReportService.selectTDamsconstructionReportList(treport);

            if (allreport.size() > 0) {
                for (TDamsconstructionReport tDamsconstructionReport : allreport) {
                    tDamsconstructionReport = tDamsconstructionReportService.selectTDamsconstructionReportByGid(tDamsconstructionReport.getGid());
                    List<TDamsconstrctionReportDetail> details = tDamsconstructionReport.gettDamsconstrctionReportDetailList();
                    allreportpoint.addAll(details);
                }
            }

            for (TDamsconstrctionReportDetail next : allreportpoint) {
                List<Coordinate> rlist = JSONArray.parseArray(next.getRanges(), Coordinate.class);
                if (rlist.size() == 1) {
                    Coordinate tempc = rlist.get(0);
                    double xxd = tempc.getOrdinate(0) - rollingDataRange.getMinCoordX();
                    int portx = (int) xxd;
                    double yyd = tempc.getOrdinate(1) - rollingDataRange.getMinCoordY();
                    int porty = (int) yyd;
                    if (null != matrix[portx][porty]) {
                        reportdetailsmap.put(next.getGid(), matrix[portx][porty]);
                    }
                }
            }


//            System.out.println(xSize+">"+ySize);
//            int x=0;
//            for (MatrixItemNews[] matrixItemNews : matrix) {
//                int y =0;
//                for (MatrixItemNews matrixItemNew : matrixItemNews) {
//                    try {
//                        if(null!=matrixItemNew){
//                            List<RollingData> alld = matrixItemNew.getAlldata();
//                            if(null!=alld&& alld.size()>0){
//                                for (RollingData rollingData : alld) {
//                                    cangAllitems.setCarid(Long.valueOf(rollingData.getVehicleID()));
//                                    cangAllitems.setPx(Long.valueOf(x));
//                                    cangAllitems.setPy(Long.valueOf(y));
//                                    cangAllitems.setSpeed((long) (rollingData.getSpeed()*100.0));
//                                    cangAllitems.setVcv((long) (rollingData.getVibrateValue()*100.0));
//                                    cangAllitems.setPz((long) (rollingData.getElevation()*100.0));
//                                    cangAllitems.setTimes(rollingData.getTimestamp());
//                                    allitemsList.add(cangAllitems);
//                                }
//                        }
//
//                    }
//
//                        if(allitemsList.size() >10000){
//                            System.out.println("保存1W条=="+x+">"+y);
//                            cangAllitemsMapper.inserbatch("cang_allitems",allitemsList);
//                            allitemsList.clear();
//                        }
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    }
//                    y++;
//                }
//                   x++;
//            }
//            if(allitemsList.size()>0){
//                cangAllitemsMapper.inserbatch("cang_allitems",allitemsList);
//                allitemsList.clear();
//            }

            System.out.println("数据保存完成。");

            //如果是摊铺推平 需要先计算出所有碾压区域的最后一个后点高程的平均值，然后通过与平均值的差值展示显示平整度
            //   MatrixItemNews[][] items = matrix;
            Double begin_evolution = damsConstruction.getGaochengact();
            Double target_houdu = damsConstruction.getCenggao();
            //推平结果处理
            if (cartype == 2) {
                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            if (dataSizetotal == 0) {
                                break;
                            }
                            try {
                                MatrixItemNews m = matrix[i][j];
                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> evlist = m.getAlldata().stream().map(RollingData::getCurrentEvolution).collect(Collectors.toList());
                                    Float currentevolution2 = evlist.size() == 0 ? 0f : evlist.get(evlist.size() - 1);
                                    float tphoudu = 100.0f * (currentevolution2 - begin_evolution.floatValue()) - target_houdu.floatValue();
                                    if (tphoudu != 0) {
                                        tphoudu = 8;
                                    }
                                    g2Evolution.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                    calculateRollingEvolution(tphoudu, rollingResult, colorConfigs44);
                                    g2Evolution.fillRect(i, j, 2, 2);
                                }


                            } catch (Exception e) {
                                log.error("出现错误。");
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                e.printStackTrace(new PrintStream(baos));
                                String exception = baos.toString();
                                log.error(exception);
                            }
                        }
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    ImageIO.write(biEvolution, "PNG", baos);
                    byte[] bytes = baos.toByteArray();//转换成字节
                    bsae64_string_evolution = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                    baos.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            } else if (cartype == 1) {//碾压结果处理
                String codekey = damsConstruction.getPid() + "_" + damsConstruction.getEngcode();
                List<Float> alllastgc = new ArrayList<>();
                if (GlobCache.encode_gc.containsKey(codekey)) {
                    alllastgc = GlobCache.encode_gc.get(codekey);
                }
                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            if (dataSizetotal == 0) {
                                assert repairDataList != null;
                                if (repairDataList.size() == 0) {
                                    break;
                                }
                            }
                            count0++;
                            count0Speed++;
                            MatrixItemNews m = matrix[i][j];
                            Integer rollingTimes = m.getRollingTimes();

/*
                    if(rollingTimes==7){
                        rollingTimes =8;
                    }
*/
                            g2.setColor(getColorByCount2(rollingTimes, colorMap));
                            calculateRollingtimes(rollingTimes, rollingResult);
                            g2.fillRect(i, j, 2, 2);

                            // TODO: 2021/10/21  超速次数
                            //获得速度集合
                            try {
                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> speeds = m.getAlldata().stream().map(RollingData::getSpeed).collect(Collectors.toList());
                                    if (speeds != null) {
                                        g2Speed.setColor(getColorByCountSpeed(StringUtils.isNotEmpty(speeds) ? speeds.get(speeds.size() - 1) : new Float(-1), colorConfigs));
                                        calculateRollingSpeed(StringUtils.isNotEmpty(speeds) ? speeds.get(speeds.size() - 1) : new Float(-1), rollingResultSpeed, colorConfigs);
                                        g2Speed.fillRect(i, j, 2, 2);
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            try {
                                //Vibration 动静碾压
                                if (null != m && m.getAlldata().size() > 0) {
                                    //LinkedList<Double> vibrations = items2.getVibrateValueList();
                                    Long vib = m.getAlldata().stream().filter(rollingData -> rollingData.getVibrateValue() > 50).count();
                                    Integer notvib = Math.toIntExact(m.getAlldata().size() - vib + 1);
                                    Integer Dtarget = damsConstruction.getHeightIndex();
                                    if (null != Dtarget) {
                                        if (Dtarget.intValue() == damsConstruction.getFrequency().intValue()) {
                                            notvib = 0;
                                        }
                                    }

                                    // BigDecimal mi = new BigDecimal(vib*1.0/(notvib+vib)*100).setScale(2,RoundingMode.HALF_DOWN);
                                    //calculateRollingVibrate(notvib.doubleValue(), rollingResultVibration, colorConfigs1);
                                    calculateRollingDJ(notvib.doubleValue(), rollingResultVibrationJ, colorConfigs1);
                                    g2VibrationJ.setColor(getColorByCountVibrate(notvib.doubleValue(), colorConfigs1));
                                    g2VibrationJ.fillRect(i, j, 2, 2);

                                    calculateRollingDJ(vib.doubleValue(), rollingResultVibrationD, colorConfigs1);
                                    g2VibrationD.setColor(getColorByCountVibrate(vib.doubleValue(), colorConfigs1));
                                    g2VibrationD.fillRect(i, j, 2, 2);


//                                    if (null != vibrations && vibrations.size() > 0) {
//                                        try {
//                                            for (Double vcv : vibrations) {
//                                                //todo 动静碾压
//                                                if (StringUtils.isNotNull(vcv)) {
//                                                    calculateRollingVibrate(vcv, rollingResultVibration, colorConfigs6);
//                                                }
//                                            }
//                                            g2Vibration.setColor(getColorByCountVibrate(StringUtils.isNotEmpty(vibrations) ? Collections.max(vibrations) : new Double(-1), colorConfigs6));
//                                            g2Vibration.fillRect(i, j, 2, 2);
//                                        } catch (Exception ignored) {
//
//                                        }
//                                    }
                                }
                            } catch (Exception e) {
                                log.error("出现错误。");
                                e.printStackTrace();
                            }
                            //压实平整度、厚度
                            try {

                                if (null != m && m.getAlldata().size() > 0) {
                                    List<Float> evlist = m.getAlldata().stream().map(RollingData::getElevation).collect(Collectors.toList());
                                    //  Double avgev  =m.getAlldata().stream().map(RollingData::getElevation).collect(Collectors.averagingDouble(Float::doubleValue)).doubleValue();
                                    Float currentevolution2 = evlist.size() == 0 ? 0f : evlist.get(evlist.size() - 1);
                                    if (evlist.size() >= 5) {
                                        alllastgc.add(currentevolution2);
                                    }
                                    //平整度
                                    float tphoudu = 100.0f * (currentevolution2 - avgevolutiontop.floatValue());

                                    //厚度
                                    float tphoudu2 = 100.0f * (currentevolution2 - begin_evolution.floatValue());

                                    //     System.out.println("当前高程："+currentevolution2+">>平均高程："+avgev+">>初始高程"+begin_evolution+"平整度："+tphoudu+"厚度："+tphoudu2);

//                                    if(tphoudu!=0){
//                                        tphoudu =8;
//                                    }
//                                    if(tphoudu2){
//
//                                    }

                                    g2Evolution.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                    calculateRollingEvolution(tphoudu, rollingResultEvolution, colorConfigs44);
                                    g2Evolution.fillRect(i, j, 2, 2);

                                    g2Evolution2.setColor(getColorByCountEvolution(tphoudu2, colorConfigs45));
                                    calculateRollingEvolution(tphoudu2, rollingResultEvolution2, colorConfigs45);
                                    g2Evolution2.fillRect(i, j, 2, 2);
                                }


                            } catch (Exception e) {
                                log.error("出现错误。");
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                e.printStackTrace(new PrintStream(baos));
                                String exception = baos.toString();
                                log.error(exception);
                            }


                        }
                    }
                }

                //将高程数据放回到缓存中
                Float minevolution = alllastgc.stream().min(Comparator.comparing(Float::floatValue)).get();
                Float maxevolution = alllastgc.stream().max(Comparator.comparing(Float::floatValue)).get();
                Double avgevolution = alllastgc.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                TEncodeEvolution query = new TEncodeEvolution();
                query.setAreagid(damsConstruction.getPid().longValue());
                query.setEncode(Long.valueOf(damsConstruction.getEngcode()));
                query.setDamgid(Long.valueOf(damsConstruction.getId()));
                List<TEncodeEvolution> savelist = tEncodeEvolutionMapper.selectTEncodeEvolutionList(query);
                if (savelist.size() == 0) {
                    TEncodeEvolution saveone = new TEncodeEvolution();
                    saveone.setAreagid(damsConstruction.getPid().longValue());
                    saveone.setDamgid(damsConstruction.getId().longValue());
                    saveone.setMaxEvolution(new BigDecimal(maxevolution));
                    saveone.setMinEvolution(new BigDecimal(minevolution));
                    saveone.setEvolution(new BigDecimal(avgevolution));
                    saveone.setEncode(Long.valueOf(damsConstruction.getEngcode()));
                    saveone.setNormalEvolution(BigDecimal.valueOf(damsConstruction.getGaocheng()));
                    saveone.setCreateTime(new Date());
                    saveone.setDelflag("N");
                    tEncodeEvolutionMapper.insertTEncodeEvolution(saveone);
                } else {
                    TEncodeEvolution saveone = savelist.get(0);

                    Double mind = new BigDecimal(minevolution + saveone.getMinEvolution().doubleValue()).divide(new BigDecimal(2), RoundingMode.HALF_DOWN).doubleValue();
                    Double maxd = new BigDecimal(maxevolution + saveone.getMaxEvolution().doubleValue()).divide(new BigDecimal(2), RoundingMode.HALF_DOWN).doubleValue();
                    Double avgd = new BigDecimal(avgevolution + saveone.getEvolution().doubleValue()).divide(new BigDecimal(2), RoundingMode.HALF_DOWN).doubleValue();

                    saveone.setUpdateTime(new Date());
                    saveone.setMaxEvolution(new BigDecimal(maxd));
                    saveone.setMinEvolution(new BigDecimal(mind));
                    saveone.setEvolution(new BigDecimal(avgd));

                    tEncodeEvolutionMapper.updateTEncodeEvolution(saveone);

                }

                // GlobCache.encode_gc.put(codekey,alllastgc);

                System.out.println("矩形区域内一共有点" + count0 + "个");
                //count0表示所有属于当前单元工程的轨迹点 time0表示在计算完遍数后，该单元工程内没有碾压的数据
                int time0 = count0 - rollingResult.getTime1() - rollingResult.getTime2() - rollingResult.getTime3() - rollingResult.getTime4() - rollingResult.getTime5() - rollingResult.getTime6() - rollingResult.getTime7() - rollingResult.getTime8()
                        - rollingResult.getTime9() - rollingResult.getTime10() - rollingResult.getTime11() - rollingResult.getTime11Up();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0 <= 0) {
                    time0 = 0;
                }
                rollingResult.setTime0(time0);

                //速度超限
                int time0Speed = count0Speed - rollingResultSpeed.getTime1() - rollingResultSpeed.getTime2();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0Speed <= 0) {
                    time0Speed = 0;
                }
                rollingResultSpeed.setTime0(time0Speed);

                long endTime = System.currentTimeMillis();    //获取结束时间
                log.info("程序运行时间：" + (endTime - startTime) + "ms");    //输出程序运行时间
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                //超限
                ByteArrayOutputStream baosSpeed = new ByteArrayOutputStream();
                //动碾压
                ByteArrayOutputStream baosVibrationD = new ByteArrayOutputStream();
                //静碾压
                ByteArrayOutputStream baosVibrationJ = new ByteArrayOutputStream();
                //碾压平整度
                ByteArrayOutputStream baoev = new ByteArrayOutputStream();
                //碾压厚度
                ByteArrayOutputStream baoev2 = new ByteArrayOutputStream();
                //        bi = (BufferedImage) ImgRotate.imageMisro(bi,0);
                try {
                    //遍数
                    ImageIO.write(bi, "PNG", baos);
                    byte[] bytes = baos.toByteArray();//转换成字节
                    bsae64_string = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                    baos.close();
                    try {
                        //超限
                        ImageIO.write(biSpeed, "PNG", baosSpeed);
                        byte[] bytesSpeed = baosSpeed.toByteArray();//转换成字节
                        bsae64_string_speed = "data:image/png;base64," + Base64.encodeBase64String(bytesSpeed);
                        baosSpeed.close();
                        //动静碾压
                        ImageIO.write(biVibrationD, "PNG", baosVibrationD);
                        byte[] bytesVibrationD = baosVibrationD.toByteArray();//转换成字节
                        bsae64_string_vibrationD = "data:image/png;base64," + Base64.encodeBase64String(bytesVibrationD);
                        baosVibrationD.close();

                        //动静碾压
                        ImageIO.write(biVibrationJ, "PNG", baosVibrationJ);
                        byte[] bytesVibrationJ = baosVibrationJ.toByteArray();//转换成字节
                        bsae64_string_vibrationJ = "data:image/png;base64," + Base64.encodeBase64String(bytesVibrationJ);
                        baosVibrationJ.close();

                        //平整度
                        ImageIO.write(biEvolution, "PNG", baoev);
                        byte[] byteev = baoev.toByteArray();//转换成字节
                        bsae64_string_evolution = "data:image/png;base64," + Base64.encodeBase64String(byteev);
                        baoev.close();
                        //碾压厚度
                        ImageIO.write(biEvolution2, "PNG", baoev2);
                        byte[] byteev2 = baoev2.toByteArray();//转换成字节
                        bsae64_string_evolution2 = "data:image/png;base64," + Base64.encodeBase64String(byteev2);
                        baoev.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }


                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        long g2time2 = System.currentTimeMillis();
        System.out.println("生成图片耗时：" + (g2time2 - g2time1) / 1000);
        StoreHouseMap.getStoreHouses2RollingData().remove(tableName);

        ProjCoordinate projCoordinate1 = new ProjCoordinate(rollingDataRange.getMinCoordY(), rollingDataRange.getMinCoordX(), 10);
        ProjCoordinate projCoordinate2 = new ProjCoordinate(rollingDataRange.getMaxCoordY(), rollingDataRange.getMaxCoordX(), 10);

        result.put("rollingResult", rollingResult);
        result.put("rollingResultSpeed", rollingResultSpeed);
        result.put("rollingResultVibrationD", rollingResultVibrationD);
        result.put("rollingResultVibrationJ", rollingResultVibrationJ);
        // result.put("rollingResultVibrationD", rollingResult);
        result.put("rollingResultEvolution", rollingResultEvolution);
        result.put("rollingResultEvolution2", rollingResultEvolution2);
        result.put("base64", bsae64_string);
        result.put("base64Speed", bsae64_string_speed);
        //  result.put("base64VibrationD", bsae64_string_vibrationD);
        result.put("base64VibrationD", bsae64_string);
        result.put("base64VibrationJ", bsae64_string_vibrationJ);
        result.put("base64Evolution", bsae64_string_evolution);
        result.put("base64Evolution2", bsae64_string_evolution2);
        result.put("rollingDataRange", rollingDataRange);
        result.put("pointLeftBottom", projCoordinate1);
        result.put("pointRightTop", projCoordinate2);
        result.put("height", damsConstruction.getGaochengact());
        result.put("cenggao", damsConstruction.getCenggao());
        result.put("range", range);
        result.put("reportdetailsmap", reportdetailsmap);
        result.put("allreportpoint", allreportpoint);
        //边界
        result.put("ranges", damsConstruction.getRanges());
        result.put("id", damsConstruction.getId());
        List<JSONObject> images = new LinkedList<>();
        result.put("images", images);
        result.put("matriss", JSONObject.toJSONString(matrix));
        return result;
    }

    public JSONObject getHistoryPicMultiThreadingByZhuangByTodNews_Matrix(String tableName, Integer cartype) {

        JSONObject result = new JSONObject();
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));


        //工作仓 数据
        tableName = GlobCache.cartableprfix[cartype] + "_" + damsConstruction.getTablename();
        int dataSizetotal = 0;
        //查询所有数据的平均高程 用以计算平整度：
        Double avgevolutiontop = damsConstructionMapper.getdamavgevolution(tableName);
        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());
        //所有车
        List<Car> carList = carMapper.findCar();
        List<TRepairData> repairDataList = null;
        //判断工作仓数据是否存在
        // boolean isHave=StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        long start = System.currentTimeMillis();
        //不存在则进行
        MatrixItemNews[][] matrix = new MatrixItemNews[xSize][ySize];
        for (int i = 0; i < xSize; i++) {
            for (int j = 0; j < ySize; j++) {
                matrix[i][j] = new MatrixItemNews();
            }
        }

        // if(2 == dmstatus) {
        shorehouseRange.put(id, storehouseRange);
        // }
        int threadNum = CORECOUNT;
        ExecutorService exec = Executors.newFixedThreadPool(threadNum);
        // 定义一个任务集合
        List<Callable<Integer>> tasks = new LinkedList<>();
        //2 获得数据库中的数据
        for (Car car : carList) {
            if (!car.getType().equals(cartype)) {
                continue;
            }
            List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString());

            // 总数据条数
            int dataSize = rollingDataList.size();
            dataSizetotal += dataSize;
            if (dataSize == 0) {
                continue;
            }
            System.out.println("即将处理》》" + dataSize + "条数据。。");

            //如果是摊铺 需要计算出所有数据的

            //每个线程处理的数据量
            int dataSizeEveryThread = dataSize / (threadNum);
            // 创建一个线程池


            Callable<Integer> task = null;
            List<RollingData> cutList = null;

            // 确定每条线程的数据
            for (int i = 0; i < threadNum; i++) {

                try {
                    if (i == threadNum - 1) {
                        int index = Math.max(dataSizeEveryThread * i - 1, 0);
                        cutList = rollingDataList.subList(index - 1, dataSize);
                    } else {
                        if (i == 0) {
                            cutList = rollingDataList.subList(0, dataSizeEveryThread * (i + 1));
                        } else {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i - 2, dataSizeEveryThread * (i + 1));
                        }
                    }
                    final List<RollingData> listRollingData = cutList;
                    task = new CalculateGridForZhuangHistoryNews();
                    ((CalculateGridForZhuangHistoryNews) task).setRollingDataRange(rollingDataRange);
                    ((CalculateGridForZhuangHistoryNews) task).setRollingDataList(listRollingData);
                    ((CalculateGridForZhuangHistoryNews) task).setTableName(tableName);
                    ((CalculateGridForZhuangHistoryNews) task).setxNum(xSize);
                    ((CalculateGridForZhuangHistoryNews) task).setyNum(ySize);
                    ((CalculateGridForZhuangHistoryNews) task).setCartype(cartype);
                    ((CalculateGridForZhuangHistoryNews) task).setTempmatrix(matrix);
                    // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                    tasks.add(task);
                } catch (Exception e) {
                    System.out.println("仓位无数据");
                }
            }
            try {
                List<Future<Integer>> results = exec.invokeAll(tasks);
                for (Future<Integer> future : results) {
                    future.get();
                }
                // 关闭线程池
                log.info("线程任务执行结束");
            } catch (Exception e) {
                e.printStackTrace();
            }
            tasks.clear();
        }
        exec.shutdown();
        log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");
        System.out.println(xSize + ">" + ySize);
        List<CangAllitems> allitemsList = new ArrayList<>();
        CangAllitems cangAllitems = new CangAllitems();

        int x = 0;
        for (MatrixItemNews[] matrixItemNews : matrix) {
            int y = 0;
            for (MatrixItemNews matrixItemNew : matrixItemNews) {
                try {
                    if (null != matrixItemNew) {
                        List<RollingData> alld = matrixItemNew.getAlldata();
                        if (null != alld && alld.size() > 0) {
                            cangAllitems = new CangAllitems();
                            RollingData rollingData = alld.get(alld.size() - 1);
                            //for (RollingData rollingData : alld) {
                            cangAllitems.setCarid(Long.valueOf(rollingData.getVehicleID()));
                            cangAllitems.setPx(Long.valueOf(x));
                            cangAllitems.setPy(Long.valueOf(y));
                            cangAllitems.setSpeed((long) (rollingData.getSpeed() * 100.0));
                            cangAllitems.setVcv((long) (rollingData.getVibrateValue() * 100.0));
                            cangAllitems.setPz((long) (rollingData.getElevation() * 100.0));
                            cangAllitems.setTimes(rollingData.getTimestamp());
                            allitemsList.add(cangAllitems);
                            // }
                        }

                    }

                    if (allitemsList.size() > 10000) {
                        System.out.println("保存1W条==" + x + ">" + y);
                        cangAllitemsMapper.inserbatch("cang_allitems_" + damsConstruction.getTablename(), allitemsList);
                        allitemsList.clear();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                y++;
            }
            x++;
        }
        if (allitemsList.size() > 0) {
            cangAllitemsMapper.inserbatch("cang_allitems", allitemsList);
            allitemsList.clear();
        }


        System.out.println("数据保存完成。");

        //  GlobCache.CANGITEMS.put(damsConstruction.getId() + "", matrix);


        return result;
    }

    /**
     * 实时碾压加载所有开仓的数据信息生成matrixitem 只生成碾压遍数数据。放入缓存中。
     *
     * @param tableName
     * @param cartype
     * @return
     */
    public void getcangmatrixitemsbytypes(String cangid, Integer cartype) {
        JSONObject result = new JSONObject();
        String id = cangid;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));
        //工作仓 数据
        String tableName = GlobCache.cartableprfix[cartype] + "_" + damsConstruction.getTablename();
        int dataSize;
        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());
        //所有车
        List<Car> carList = carMapper.findCar();
        List<TRepairData> repairDataList;
        //判断工作仓数据是否存在
        // boolean isHave=StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        long start = System.currentTimeMillis();
        //不存在则进行
        MatrixItem[][] matrix = new MatrixItem[xSize][ySize];
        for (int i = 0; i < xSize; i++) {
            for (int j = 0; j < ySize; j++) {
                matrix[i][j] = new MatrixItem();
                matrix[i][j].setRollingTimes(0);
            }
        }

        // if(2 == dmstatus) {
        shorehouseRange.put(id, storehouseRange);
        StoreHouseMap.getStoreHouses2RollingDataCang().put(tableName, matrix);
        // }
        List<RollingData> rollingDataList_all = new LinkedList<>();

        //2 获得数据库中的数据
        for (Car car : carList) {
            List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString());
            rollingDataList_all.addAll(rollingDataList);
        }
        // 总数据条数
        dataSize = rollingDataList_all.size();
        System.out.println("即将处理》》" + dataSize + "条数据。。");

        //如果是摊铺 需要计算出所有数据的

        // 线程数 四个核心
        int threadNum = dataSize == 0 ? 1 : CORECOUNT;

        //每个线程处理的数据量
        int dataSizeEveryThread = dataSize / (threadNum);
        // 创建一个线程池
        ExecutorService exec = Executors.newFixedThreadPool(threadNum);

        // 定义一个任务集合
        List<Callable<Integer>> tasks = new LinkedList<>();
        CalculateGridForZhuangHistory task;
        List<RollingData> cutList;

        // 确定每条线程的数据
        for (int i = 0; i < threadNum; i++) {

            try {
                if (i == threadNum - 1) {
                    int index = Math.max(dataSizeEveryThread * i - 1, 0);
                    cutList = rollingDataList_all.subList(index - 1, dataSize);
                } else {
                    if (i == 0) {
                        cutList = rollingDataList_all.subList(0, dataSizeEveryThread * (i + 1));
                    } else {
                        cutList = rollingDataList_all.subList(dataSizeEveryThread * i - 2, dataSizeEveryThread * (i + 1));
                    }
                }
                final List<RollingData> listRollingData = cutList;
                task = new CalculateGridForZhuangHistory();
                task.setRollingDataRange(rollingDataRange);
                task.setRollingDataList(listRollingData);
                task.setTableName(tableName);
                task.setxNum(xSize);
                task.setyNum(ySize);
                task.setCartype(cartype);
                // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                tasks.add(task);
            } catch (Exception e) {
                System.out.println("仓位无数据");
            }
        }

        try {
            List<Future<Integer>> results = exec.invokeAll(tasks);
            for (Future<Integer> future : results) {
                future.get();
            }
            // 关闭线程池
            exec.shutdown();
            log.info("线程任务执行结束");
        } catch (Exception e) {
            e.printStackTrace();
        }

        log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");

    }

    private List<TRepairData> gettRepairData(String tableName, Integer cartype, DamsConstruction damsConstruction, RollingDataRange rollingDataRange, MatrixItemNews[][] items) {
        List<TRepairData> repairDataList;
        TRepairData record = new TRepairData();
        record.setDamsid(damsConstruction.getId());
        record.setCartype(cartype);
        repairDataList = repairDataMapper.selectTRepairDatas(record);
        //根据补录内容 将仓对应区域的碾压遍数、速度、压实度更新
        if (repairDataList != null && repairDataList.size() > 0) {

            MatrixItemNews[][] matrixItems = items;
            for (TRepairData repairData : repairDataList) {
                String ranges = repairData.getRanges();
                int passCount = repairData.getColorId();
                float speed = repairData.getSpeed().floatValue();
                double vibration = repairData.getVibration();

                List<Coordinate> repairRange = JSONArray.parseArray(ranges, Coordinate.class);
                Pixel[] repairPolygon = new Pixel[repairRange.size()];
                for (int i = 0; i < repairRange.size(); i++) {
                    Coordinate coordinate = repairRange.get(i);
                    Pixel pixel = new Pixel();
                    pixel.setX((int) coordinate.x);
                    pixel.setY((int) coordinate.y);
                    repairPolygon[i] = pixel;
                }
                List<Pixel> rasters = Scan.scanRaster(repairPolygon);
                int bottom = (int) (rollingDataRange.getMinCoordY() * 1);
                int left = (int) (rollingDataRange.getMinCoordX() * 1);
                int width = (int) (rollingDataRange.getMaxCoordX() - rollingDataRange.getMinCoordX());
                int height = (int) (rollingDataRange.getMaxCoordY() - rollingDataRange.getMinCoordY());
                int n = 0;
                int m = 0;
                int time10 = 0;
                RollingData temp = new RollingData();
                for (Pixel pixel : rasters) {
                    try {

                        n = (pixel.getY() - bottom);
                        m = (pixel.getX() - left);
                        if (n >= 0 && m >= 0 && n < height && m < width) {
                            if (matrixItems[m][n] == null) {
                                matrixItems[m][n] = new MatrixItemNews();

                            }
                            temp.setSpeed(speed);
                            temp.setVibrateValue(vibration);
                            Double houdu = damsConstruction.getGaocheng() + (RandomUtiles.randomdouble(damsConstruction.getHoudu() - 5, damsConstruction.getHoudu() + 5)) * 0.01;
                            temp.setElevation(houdu.floatValue());
                            temp.setCurrentEvolution(houdu.floatValue());
                            matrixItems[m][n].setRollingTimes(damsConstruction.getFrequency() + 1);
                            matrixItems[m][n].getAlldata().add(temp);

                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                // StoreHouseMap.getStoreHouses2RollingDataNews().put(tableName, matrixItems);
                rasters.clear();
            }
        }
        return repairDataList;
    }

    /**
     * 闭仓是计算合格率
     *
     * @param userName  用户
     * @param tableName 仓位
     * @param cartype   类型
     * @return
     */
    public JSONObject getdam_rotate(String userName, String tableName, Integer cartype) {
        JSONObject result = new JSONObject();
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));
        int dmstatus = damsConstruction.getStatus();
        //工作仓 数据
        tableName = GlobCache.cartableprfix[cartype] + "_" + damsConstruction.getTablename();
        int dataSize = 0;
        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());
        //所有车
        List<Car> carList = carMapper.findCar();
        List<TRepairData> repairDataList;
        //判断工作仓数据是否存在
        // boolean isHave=StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        long start = System.currentTimeMillis();
        if (true) {//不存在则进行
            MatrixItem[][] matrix = new MatrixItem[xSize][ySize];
            for (int i = 0; i < xSize; i++) {
                for (int j = 0; j < ySize; j++) {
                    matrix[i][j] = new MatrixItem();
                    matrix[i][j].setRollingTimes(0);
                }
            }

            // if(2 == dmstatus) {
            shorehouseRange.put(id, storehouseRange);
            StoreHouseMap.getStoreHouses2RollingData().put(tableName, matrix);
            // }
            List<RollingData> rollingDataList_all = new LinkedList<>();

            //2 获得数据库中的数据
            for (Car car : carList) {
                List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString());
                rollingDataList_all.addAll(rollingDataList);
            }
            // 总数据条数
            dataSize = rollingDataList_all.size();
            System.out.println("即将处理》》" + dataSize + "条数据。。");

            //如果是摊铺 需要计算出所有数据的

            // 线程数 四个核心
            int threadNum = dataSize == 0 ? 1 : CORECOUNT;
            // 余数
            int special = dataSize % threadNum;
            //每个线程处理的数据量
            int dataSizeEveryThread = dataSize / threadNum;
            // 创建一个线程池
            ExecutorService exec = Executors.newFixedThreadPool(threadNum);

            // 定义一个任务集合
            List<Callable<Integer>> tasks = new LinkedList<Callable<Integer>>();
            Callable<Integer> task = null;
            List<RollingData> cutList = null;

            // 确定每条线程的数据
            for (int i = 0; i < threadNum; i++) {

                try {
                    if (i == threadNum - 1) {
                        int index = dataSizeEveryThread * i - 1 < 0 ? 0 : dataSizeEveryThread * i - 1;
                        cutList = rollingDataList_all.subList(index, dataSize);
                    } else {
                        if (i == 0) {
                            cutList = rollingDataList_all.subList(dataSizeEveryThread * i, dataSizeEveryThread * (i + 1));
                        } else {
                            cutList = rollingDataList_all.subList(dataSizeEveryThread * i - 1, dataSizeEveryThread * (i + 1));
                        }
                    }
                    final List<RollingData> listRollingData = cutList;
                    task = new CalculateGridForZhuangHistoryForCloseCang();
                    ((CalculateGridForZhuangHistoryForCloseCang) task).setRollingDataRange(rollingDataRange);
                    ((CalculateGridForZhuangHistoryForCloseCang) task).setRollingDataList(listRollingData);
                    ((CalculateGridForZhuangHistoryForCloseCang) task).setTableName(tableName);
                    ((CalculateGridForZhuangHistoryForCloseCang) task).setxNum(xSize);
                    ((CalculateGridForZhuangHistoryForCloseCang) task).setyNum(ySize);
                    // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                    tasks.add(task);
                } catch (Exception e) {
                    System.out.println("仓位无数据");
                }
            }

            try {
                List<Future<Integer>> results = exec.invokeAll(tasks);
                for (Future<Integer> future : results) {
                    log.info(future.get().toString());
                }
                // 关闭线程池
                exec.shutdown();
                log.info("线程任务执行结束");
            } catch (Exception e) {
                e.printStackTrace();
            }

            /**
             * 查询出当前仓位的补录区域
             */
            TRepairData record = new TRepairData();
            record.setDamsid(damsConstruction.getId());
            record.setCartype(cartype);
            repairDataList = repairDataMapper.selectTRepairDatas(record);
            //根据补录内容 将仓对应区域的碾压遍数、速度、压实度更新
            if (repairDataList != null && repairDataList.size() > 0) {
                Map<String, MatrixItem[][]> storeHouses2RollingData = StoreHouseMap.getStoreHouses2RollingData();
                MatrixItem[][] matrixItems = storeHouses2RollingData.get(tableName);
                for (TRepairData repairData : repairDataList) {
                    String ranges = repairData.getRanges();
                    int passCount = repairData.getColorId();
                    float speed = repairData.getSpeed().floatValue();
                    double vibration = repairData.getVibration();

                    List<Coordinate> repairRange = JSONArray.parseArray(ranges, Coordinate.class);
                    Pixel[] repairPolygon = new Pixel[repairRange.size()];
                    for (int i = 0; i < repairRange.size(); i++) {
                        Coordinate coordinate = repairRange.get(i);
                        Pixel pixel = new Pixel();
                        pixel.setX((int) coordinate.x);
                        pixel.setY((int) coordinate.y);
                        repairPolygon[i] = pixel;
                    }
                    List<Pixel> rasters = Scan.scanRaster(repairPolygon);
                    int bottom = (int) (rollingDataRange.getMinCoordY() * 1);
                    int left = (int) (rollingDataRange.getMinCoordX() * 1);
                    int width = (int) (rollingDataRange.getMaxCoordX() - rollingDataRange.getMinCoordX());
                    int height = (int) (rollingDataRange.getMaxCoordY() - rollingDataRange.getMinCoordY());
                    int n = 0;
                    int m = 0;
                    for (Pixel pixel : rasters) {
                        try {
                            n = (pixel.getY() - bottom);
                            m = (pixel.getX() - left);
                            if (n >= 0 && m >= 0 && n < height && m < width) {
                                MatrixItem item = matrixItems[m][n];
                                if (item == null) {
                                    item = new MatrixItem();
                                    matrixItems[m][n] = item;
                                }
                                item.setRollingTimes(passCount);
                                item.getSpeedList().add(speed);
                                item.getVibrateValueList().add(vibration);
                            }
                        } catch (Exception ex) {
                            log.error("(" + m + "," + n + ")像素错误:" + ex.getMessage());
                        }
                    }
                    rasters.clear();
                    rasters = null;
                }
            }
            log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");
        }
        RollingResult rollingResult = new RollingResult();
        //超限
        RollingResult rollingResultSpeed = new RollingResult();
        //动静碾压
        RollingResult rollingResultVibration = new RollingResult();
        RollingResult rollingResultEvolution = new RollingResult();
        int count0 = 0;
        int count0Speed = 0;
        int count0Vibration = 0;
        int count0Evolution = 0;
        String bsae64_string = "";
        String bsae64_string_speed = "";
        String bsae64_string_vibration = "";
        String bsae64_string_evolution = "";
        Map<Integer, Color> colorMap = getColorMap(GlobCache.carcoloconfigtype[cartype].longValue());

        TColorConfig vo = new TColorConfig();
        vo.setType(3L);//超限
        List<TColorConfig> colorConfigs = colorConfigMapper.select(vo);
        vo.setType(6L);//动静碾压
        List<TColorConfig> colorConfigs6 = colorConfigMapper.select(vo);
        vo.setType(44L);//摊铺平整度颜色
        List<TColorConfig> colorConfigs44 = colorConfigMapper.select(vo);
        Map<Long, MatrixItem> reportdetailsmap = new HashMap<>();
        List<TDamsconstrctionReportDetail> allreportpoint = new LinkedList<>();
        synchronized (obj1) {// 同步代码块

            List<Coordinate> list = JSONArray.parseArray(damsConstruction.getRanges(), Coordinate.class);
            Coordinate[] pointList = new Coordinate[list.size() + 1];  //最重要，不能遗漏，预先申请好数组空间
            list.toArray(pointList);
            pointList[list.size()] = pointList[0];
            //记录遍数
            GeometryFactory gf = new GeometryFactory();
            Geometry edgePoly = gf.createPolygon(pointList);
            long startTime = System.currentTimeMillis();

            //查询该区域是否存在试验点数据。
            TDamsconstructionReport treport = new TDamsconstructionReport();
            treport.setDamgid(Long.valueOf(damsConstruction.getId()));
            List<TDamsconstructionReport> allreport = tDamsconstructionReportService.selectTDamsconstructionReportList(treport);

            if (allreport.size() > 0) {
                for (TDamsconstructionReport tDamsconstructionReport : allreport) {
                    tDamsconstructionReport = tDamsconstructionReportService.selectTDamsconstructionReportByGid(tDamsconstructionReport.getGid());

                    List<TDamsconstrctionReportDetail> details = tDamsconstructionReport.gettDamsconstrctionReportDetailList();
                    allreportpoint.addAll(details);
                }
            }
            for (Iterator<TDamsconstrctionReportDetail> iterator = allreportpoint.iterator(); iterator.hasNext(); ) {
                TDamsconstrctionReportDetail next = iterator.next();
                List<Coordinate> rlist = JSONArray.parseArray(next.getRanges(), Coordinate.class);
                if (rlist.size() == 1) {
                    Coordinate tempc = rlist.get(0);
                    Double xxd = tempc.getOrdinate(0) - rollingDataRange.getMinCoordX().doubleValue();
                    int portx = xxd.intValue();
                    Double yyd = tempc.getOrdinate(1) - rollingDataRange.getMinCoordY().doubleValue();
                    int porty = yyd.intValue();

                    if (null != StoreHouseMap.getStoreHouses2RollingData().get(tableName)[portx][porty]) {
                        reportdetailsmap.put(next.getGid(), StoreHouseMap.getStoreHouses2RollingData().get(tableName)[portx][porty]);
                    }
                }

            }


            if (cartype == 1) {//碾压结果处理
                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        Double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        Double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            if (dataSize == 0 && repairDataList.size() == 0) {
                                break;
                            }
                            count0++;
                            count0Speed++;
                            count0Vibration++;
                            Integer rollingTimes = StoreHouseMap.getStoreHouses2RollingData().get(tableName)[i][j].getRollingTimes();
                            calculateRollingtimes(rollingTimes, rollingResult);

                        }
                    }
                }

                //count0表示所有属于当前单元工程的轨迹点 time0表示在计算完遍数后，该单元工程内没有碾压的数据
                int time0 = count0 - rollingResult.getTime1() - rollingResult.getTime2() - rollingResult.getTime3() - rollingResult.getTime4() - rollingResult.getTime5() - rollingResult.getTime6() - rollingResult.getTime7() - rollingResult.getTime8()
                        - rollingResult.getTime9() - rollingResult.getTime10() - rollingResult.getTime11() - rollingResult.getTime11Up();
                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
                if (time0 <= 0) {
                    time0 = 0;
                }
                rollingResult.setTime0(time0);

//                //速度超限
//                int time0Speed = count0Speed - rollingResultSpeed.getTime1() - rollingResultSpeed.getTime2();
//                //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
//                if (time0Speed <= 0) {
//                    time0Speed = 0;
//                }
//                rollingResultSpeed.setTime0(time0Speed);

            }
        }

        StoreHouseMap.getStoreHouses2RollingData().remove(tableName);


        result.put("rollingResult", rollingResult);

        return result;
    }

    private void calculateRollingtimes(Integer rollingTimes, RollingResult rollingResult) {
        if (rollingTimes.equals(0)) {
            rollingResult.setTime0(rollingResult.getTime0() + 1);
        }
        if (rollingTimes.equals(1)) {
            rollingResult.setTime1(rollingResult.getTime1() + 1);
        }
        if (rollingTimes.equals(2)) {
            rollingResult.setTime2(rollingResult.getTime2() + 1);
        }
        if (rollingTimes.equals(3)) {
            rollingResult.setTime3(rollingResult.getTime3() + 1);
        }
        if (rollingTimes.equals(4)) {
            rollingResult.setTime4(rollingResult.getTime4() + 1);
        }
        if (rollingTimes.equals(5)) {
            rollingResult.setTime5(rollingResult.getTime5() + 1);
        }
        if (rollingTimes.equals(6)) {
            rollingResult.setTime6(rollingResult.getTime6() + 1);
        }
        if (rollingTimes.equals(7)) {
            rollingResult.setTime8(rollingResult.getTime8() + 1);
        }
        if (rollingTimes.equals(8)) {
            rollingResult.setTime8(rollingResult.getTime8() + 1);
        }
        if (rollingTimes > 8) {
            rollingResult.setTime9(rollingResult.getTime9() + 1);
        }
    }

    public Color getColorByCount2(Integer count, Map<Integer, Color> colorMap) {
        Color color = colorMap.get(count);
        if (StringUtils.isNotNull(color)) {
            return color;
        } else {
            if (count.intValue() > 0) {
                Integer maxKey = (Integer) MapUtil.getMaxKey(colorMap);//取最大key
                return colorMap.get(maxKey);
            } else if (count.intValue() == 0) {
                return new Color(255, 255, 255, 0);
            }
        }
        return null;
    }

    public Color getColorByCountSpeed(Float count, List<TColorConfig> colorConfigs) {
        for (TColorConfig color : colorConfigs) {
            //判断count 在哪个从和到之间
            if (count >= color.getC().floatValue() && count < color.getD().floatValue()) {
                int[] rgb = RGBHexUtil.hex2RGB(color.getColor());
                return new Color(rgb[0], rgb[1], rgb[2]);
            }
        }
        //匹配不到的情况下
        return new Color(255, 255, 255, 0);
    }

    private void calculateRollingSpeed(float speed, RollingResult rollingResult, List<TColorConfig> colorConfigs) {
        for (TColorConfig color : colorConfigs) {
            //判断count 在哪个从和到之间
            if (speed >= color.getC().floatValue() && speed < color.getD().floatValue()) {
                if (color.getLevel().intValue() == 1) {
                    rollingResult.setTime0(rollingResult.getTime0() + 1);
                } else if (color.getLevel().intValue() == 2) {
                    rollingResult.setTime1(rollingResult.getTime1() + 1);
                }
            }
        }
    }

    public Color getColorByCountVibrate(Double count, List<TColorConfig> colorConfigs) {
        for (TColorConfig color : colorConfigs) {
            //判断count 在哪个从和到之间
            if (count >= color.getC().doubleValue() && count < color.getD().doubleValue()) {
                int[] rgb = RGBHexUtil.hex2RGB(color.getColor());
                return new Color(rgb[0], rgb[1], rgb[2]);
            }
        }
        //匹配不到的情况下
        return new Color(255, 255, 255, 0);
    }

    public Color getColorByCountEvolution(Float count, List<TColorConfig> colorConfigs) {
        for (TColorConfig color : colorConfigs) {
            //判断count 在哪个从和到之间
            if (count >= color.getC().doubleValue() && count <= color.getD().doubleValue()) {
                int[] rgb = RGBHexUtil.hex2RGB(color.getColor());
                return new Color(rgb[0], rgb[1], rgb[2]);
            }
        }
        //匹配不到的情况下
        int[] rgb = RGBHexUtil.hex2RGB(colorConfigs.get(0).getColor());
        return new Color(rgb[0], rgb[1], rgb[2]);
        // return new Color(255, 255, 255, 0);
    }

    private void calculateRollingVibrate(Double vibrate, RollingResult rollingResult, List<TColorConfig> colorConfigs) {
        for (TColorConfig color : colorConfigs) {
            //判断count 在哪个从和到之间
            if (vibrate >= color.getC().doubleValue() && vibrate <= color.getD().doubleValue()) {
                if (color.getLevel().intValue() == 1) {
                    rollingResult.setTime0(rollingResult.getTime0() + 1);
                } else if (color.getLevel().intValue() == 2) {
                    rollingResult.setTime1(rollingResult.getTime1() + 1);
                }
            }
        }
    }

    private void calculateRollingEvolution(float depth, RollingResult rollingResult, List<TColorConfig> colorConfigs) {
        for (TColorConfig color : colorConfigs) {
            //判断count 在哪个从和到之间
            if (depth >= color.getC().doubleValue() && depth < color.getD().doubleValue()) {
//                if (color.getLevel().intValue() == 1) {
//                    rollingResult.setTime0(rollingResult.getTime0() + 1);
//                } else if (color.getLevel().intValue() == 2) {
//                    rollingResult.setTime1(rollingResult.getTime1() + 1);
//                } else if (color.getLevel().intValue() == 3) {
//                    rollingResult.setTime1(rollingResult.getTime1() + 1);
//                }
                switch (color.getLevel().intValue()) {
                    case 1: {
                        rollingResult.setTime0(rollingResult.getTime0() + 1);
                        break;
                    }
                    case 2: {
                        rollingResult.setTime1(rollingResult.getTime1() + 1);
                        break;
                    }
                    case 3: {
                        rollingResult.setTime2(rollingResult.getTime2() + 1);
                        break;
                    }
                    case 4: {
                        rollingResult.setTime3(rollingResult.getTime3() + 1);
                        break;
                    }
                    case 5: {
                        rollingResult.setTime4(rollingResult.getTime4() + 1);
                        break;
                    }
                    case 6: {
                        rollingResult.setTime5(rollingResult.getTime5() + 1);
                        break;
                    }
                    case 7: {
                        rollingResult.setTime6(rollingResult.getTime6() + 1);
                        break;
                    }
                    default: {
                        break;
                    }
                }
            }
        }
    }

    private void calculateRollingDJ(Double vibrate, RollingResult rollingResult, List<TColorConfig> colorConfigs) {
        for (TColorConfig color : colorConfigs) {
            //判断count 在哪个从和到之间
            if (vibrate >= color.getC().doubleValue() && vibrate < color.getD().doubleValue()) {
//                if (color.getLevel().intValue() == 1) {
//                    rollingResult.setTime0(rollingResult.getTime0() + 1);
//                } else if (color.getLevel().intValue() == 2) {
//                    rollingResult.setTime1(rollingResult.getTime1() + 1);
//                } else if (color.getLevel().intValue() == 3) {
//                    rollingResult.setTime1(rollingResult.getTime1() + 1);
//                }
                if (color.getLevel() == 0) {
                    rollingResult.setTime0(rollingResult.getTime0());
                }
                if (color.getLevel() == 1) {
                    rollingResult.setTime1(rollingResult.getTime1() + 1);
                }
                if (color.getLevel() == 2) {
                    rollingResult.setTime2(rollingResult.getTime2() + 1);
                }
                if (color.getLevel() == 3) {
                    rollingResult.setTime3(rollingResult.getTime3() + 1);
                }
                if (color.getLevel() == 4) {
                    rollingResult.setTime4(rollingResult.getTime4() + 1);
                }
                if (color.getLevel() == 5) {
                    rollingResult.setTime5(rollingResult.getTime5() + 1);
                }
                if (color.getLevel() == 6) {
                    rollingResult.setTime6(rollingResult.getTime6() + 1);
                }
                if (color.getLevel() == 7) {
                    rollingResult.setTime7(rollingResult.getTime7() + 1);
                }
                if (color.getLevel() == 8) {
                    rollingResult.setTime8(rollingResult.getTime8() + 1);
                }
                if (color.getLevel() == 9) {
                    rollingResult.setTime9(rollingResult.getTime9() + 1);
                }
                if (color.getLevel() > 9) {
                    rollingResult.setTime10(rollingResult.getTime10() + 1);
                }


            }
        }
    }

    public Map<Integer, Color> getColorMap(Long type) {
        Map<Integer, Color> colorMap = new HashMap<>();
        TColorConfig vo = new TColorConfig();
        vo.setType(type);//碾压遍次
        List<TColorConfig> colorConfigs = colorConfigMapper.select(vo);
        for (TColorConfig color : colorConfigs) {
            if (color.getNum().intValue() == 0) {
                colorMap.put(0, new Color(255, 255, 255, 0));
            } else {
                int[] rgb = RGBHexUtil.hex2RGB(color.getColor());
                colorMap.put(Integer.valueOf(String.valueOf(color.getNum())), new Color(rgb[0], rgb[1], rgb[2]));
            }
        }
        return colorMap;
    }

    public String clearMatrix(String userName) {
        /*清除storehouseRange*/
        /*清除storehouseMaos2RollingData*/
        Iterator<String> iterator = shorehouseRange.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            String[] split1 = key.split("-");
            String userNameOfMatrix = split1[0];
            if (userName.equalsIgnoreCase(userNameOfMatrix)) {
                iterator.remove();
            }
        }
        Iterator<String> iterator2 = StoreHouseMap.getStoreHouses2RollingData().keySet().iterator();
        while (iterator2.hasNext()) {
            String key = iterator2.next();
            String[] split1 = key.split("-");
            String userNameOfMatrix = split1[0];
            if (userName.equalsIgnoreCase(userNameOfMatrix)) {
                iterator2.remove();
            }
        }
        return "清除该用户对应的内存";
    }

    /**
     * 碾压
     *
     * @return
     */
    public QualifiedRate getQualifiedRate(RollingResult rollingresult, int target) {

        //获取分析参数设置
        TAnalysisConfig tAnalysisConfig = tanalysisConfigMapper.selectMaxIdOne();
        //将碾压遍数放入列表
        List<Integer> rollingTimeList = new LinkedList<>();
        rollingTimeList.add(rollingresult.getTime0());
        rollingTimeList.add(rollingresult.getTime1());
        rollingTimeList.add(rollingresult.getTime2());
        rollingTimeList.add(rollingresult.getTime3());
        rollingTimeList.add(rollingresult.getTime4());
        rollingTimeList.add(rollingresult.getTime5());
        rollingTimeList.add(rollingresult.getTime6());
        rollingTimeList.add(rollingresult.getTime7());
        rollingTimeList.add(rollingresult.getTime8());
        rollingTimeList.add(rollingresult.getTime9());
        rollingTimeList.add(rollingresult.getTime10());
        rollingTimeList.add(rollingresult.getTime11());
        rollingTimeList.add(rollingresult.getTime11Up());

        //计算总的碾压
        int alltime = 0;
        int qualifiedtime = 0;
        int num = target;//碾压遍次限制默认值
//        if (StringUtils.isNotNull(tAnalysisConfig)) {
//            if (StringUtils.isNotNull(tAnalysisConfig.getNum())) {
//                num = Integer.valueOf(String.valueOf(tAnalysisConfig.getNum()));
//            }
//        }
        for (int i = 1; i < 13; i++) {
            alltime += rollingTimeList.get(i);
            if (i >= num) {
                qualifiedtime += rollingTimeList.get(i);
            }
        }

        return new QualifiedRate(alltime, qualifiedtime);
    }

    public JSONObject unitreport_track_zhuanghao(String userName, String tableName, int cartype) throws InterruptedException, ExecutionException, IOException {
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));

        int dmstatus = damsConstruction.getStatus();
        //工作仓 数据
        tableName = damsConstruction.getTablename();

        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());

        //获取图片旋转角度
        float angele = getrotate(damsConstruction);

        //所有车

        List<Car> carList = carMapper.findCar();
        //判断工作仓数据是否存在
        //  boolean isHave = StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        MatrixItem[][] matrix = new MatrixItem[xSize][ySize];
        if (true) {//不存在则进行
            for (int i = 0; i < xSize; i++) {
                for (int j = 0; j < ySize; j++) {
                    matrix[i][j] = new MatrixItem();
                    matrix[i][j].setRollingTimes(0);
                }
            }

            //  shorehouseRange.put(id,storehouseRange);
            // StoreHouseMap.getStoreHouses2RollingData().put(tableName, matrix);


            /**
             * 查询出当前仓位的补录区域
             */
            TRepairData record = new TRepairData();
            record.setDamsid(damsConstruction.getId());
            record.setCartype(cartype);
            List<TRepairData> repairDataList = repairDataMapper.selectTRepairDatas(record);

            //2 获得数据库中的数据
            long start = System.currentTimeMillis();
            for (Car car : carList) {

                if (car.getType() != cartype) {
                    continue;
                }

                String newtable = GlobCache.cartableprfix[car.getType()] + "_" + tableName;
                List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(newtable, car.getCarID().toString());
                // 总数据条数
                int dataSize = rollingDataList.size();
                long timedata1 = System.currentTimeMillis();
                System.out.println("自动报告将要处理：" + dataSize);
                if (dataSize < 10) {
                    continue;
                }
                if (dataSize == 0) {
                    continue;
                }
                // 线程数 四个核心
                int threadNum = CORECOUNT;

                //每个线程处理的数据量
                int dataSizeEveryThread = dataSize / (threadNum);
                // 创建一个线程池
                ExecutorService exec = Executors.newFixedThreadPool(threadNum);
                // 定义一个任务集合
                List<Callable<Integer>> tasks = new LinkedList<Callable<Integer>>();
                Callable<Integer> task = null;
                List<RollingData> cutList = null;

                // 确定每条线程的数据
                for (int i = 0; i < threadNum; i++) {
                    if (i == threadNum - 1) {
                        int index = dataSizeEveryThread * i - 1 < 0 ? 0 : dataSizeEveryThread * i - 1;
                        cutList = rollingDataList.subList(index + 1, dataSize);
                    } else {
                        if (i == 0) {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i, dataSizeEveryThread * (i + 1));
                        } else {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i, dataSizeEveryThread * (i + 1) + 2);
                        }
                    }
                    final List<RollingData> listRollingData = cutList;
                    task = new CalculateGridForZhuangForReport();
                    ((CalculateGridForZhuangForReport) task).setRollingDataRange(rollingDataRange);
                    ((CalculateGridForZhuangForReport) task).setRollingDataList(listRollingData);
                    ((CalculateGridForZhuangForReport) task).setTableName(tableName);
                    ((CalculateGridForZhuangForReport) task).setxNum(xSize);
                    ((CalculateGridForZhuangForReport) task).setyNum(ySize);
                    ((CalculateGridForZhuangForReport) task).setTempmatrix(matrix);
                    ((CalculateGridForZhuangForReport) task).setCartype(cartype);
                    // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                    tasks.add(task);
                }
                List<Future<Integer>> results = exec.invokeAll(tasks);
                for (Future<Integer> future : results) {
                    log.info(future.get().toString());
                }
                // 关闭线程池
                exec.shutdown();
                long timedata2 = System.currentTimeMillis();
                log.info("线程任务执行结束");
                System.out.println("线程耗时：" + (timedata2 - timedata1) / 1000);
            }
            //根据补录内容 将仓对应区域的碾压遍数、速度、压实度更新
            if (repairDataList != null) {
                //   MatrixItem[][] matrixItems = matrix;
                for (TRepairData repairData : repairDataList) {
                    if (repairData.getRepairtype() == 1) {
                        continue;
                    }
                    String ranges = repairData.getRanges();
                    int passCount = repairData.getColorId();
                    float speed = repairData.getSpeed().floatValue();
                    double vibration = repairData.getVibration();

                    List<Coordinate> repairRange = JSONArray.parseArray(ranges, Coordinate.class);
                    Pixel[] repairPolygon = new Pixel[repairRange.size()];
                    for (int i = 0; i < repairRange.size(); i++) {
                        Coordinate coordinate = repairRange.get(i);
                        Pixel pixel = new Pixel();
                        pixel.setX((int) coordinate.x);
                        pixel.setY((int) coordinate.y);
                        repairPolygon[i] = pixel;
                    }
                    List<Pixel> rasters = Scan.scanRaster(repairPolygon);
                    int bottom = (int) (rollingDataRange.getMinCoordY() * 1);
                    int left = (int) (rollingDataRange.getMinCoordX() * 1);
                    int width = (int) (rollingDataRange.getMaxCoordX() - rollingDataRange.getMinCoordX());
                    int height = (int) (rollingDataRange.getMaxCoordY() - rollingDataRange.getMinCoordY());
                    int n = 0;
                    int m = 0;
                    for (Pixel pixel : rasters) {
                        try {
                            n = (pixel.getY() - bottom);
                            m = (pixel.getX() - left);
                            if (n >= 0 && m >= 0 && n < height && m < width) {
                                MatrixItem item = matrix[m][n];
                                if (item == null) {
                                    item = new MatrixItem();
                                    matrix[m][n] = item;
                                }
                                if (cartype == 1) {
                                    item.setRollingTimes(passCount);
                                    item.getSpeedList().add(speed);
                                    item.getVibrateValueList().add(vibration);

                                } else if (cartype == 2) {
                                    Double houdu = damsConstruction.getGaocheng() + (RandomUtiles.randomdouble(damsConstruction.getHoudu() - 5, damsConstruction.getHoudu() + 5)) * 0.01;

                                    if (item.getCurrentEvolution().isEmpty()) {
                                        item.setCurrentEvolution(new LinkedList<>());
                                    }
                                    item.getCurrentEvolution().add(houdu.floatValue());

                                }
                            }
                        } catch (Exception ex) {
                            log.error("(" + m + "," + n + ")像素错误:" + ex.getMessage());
                        }
                    }
                    rasters.clear();
                    rasters = null;
                }
                // storeHouses2RollingData =null;
            }

            log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");
            // matrix= null;
        }


        RollingResult rollingResult = new RollingResult();
        //超限
        RollingResult rollingResultSpeed = new RollingResult();
        //动静碾压
        RollingResult rollingResultVibration = new RollingResult();
        int count0 = 0;
        int count0Speed = 0;
        int count0Vibration = 0;
        String bsae64_string = "";
        String bsae64_string_speed = "";
        String bsae64_string_vibration = "";
        TColorConfig vo = new TColorConfig();
        JSONObject result = new JSONObject();
        vo.setType(3L);//超限
        List<TColorConfig> colorConfigs = colorConfigMapper.select(vo);

        vo.setType(44L);//动静碾压
        List<TColorConfig> colorConfigs44 = colorConfigMapper.select(vo);
        long g2time1 = System.currentTimeMillis();
        synchronized (obj1) {// 同步代码块
            //绘制图片
            //得到图片缓冲区
            BufferedImage bi = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150

            //动静碾压
            BufferedImage biVibration = new BufferedImage(xSize, ySize, BufferedImage.TYPE_INT_ARGB);//INT精确度达到一定,RGB三原色，高度70,宽度150
            //得到它的绘制环境(这张图片的笔)
            Graphics2D g2 = (Graphics2D) bi.getGraphics();
            g2.transform(new AffineTransform());

            //动静碾压
            Graphics2D g2Vibration = (Graphics2D) biVibration.getGraphics();
            List<Coordinate> list = JSONArray.parseArray(damsConstruction.getRanges(), Coordinate.class);
            Coordinate[] pointList = new Coordinate[list.size() + 1];//最重要，不能遗漏，预先申请好数组空间
            list.toArray(pointList);
            pointList[list.size()] = pointList[0];
            //记录遍数
            GeometryFactory gf = new GeometryFactory();
            Geometry edgePoly = gf.createPolygon(pointList);


            Coordinate[] allcors = edgePoly.getCoordinates();

            int[] xlist = new int[allcors.length];
            int[] ylist = new int[allcors.length];
            for (int i = 0; i < allcors.length; i++) {
                Coordinate one = allcors[i];
                Double x = one.getOrdinate(0) - rollingDataRange.getMinCoordX();
                Double y = one.getOrdinate(1) - rollingDataRange.getMinCoordY();
                xlist[i] = (int) Math.round(x);
                ylist[i] = (int) Math.round(y);

            }
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(new Color(24, 157, 37));
            g2.setStroke(new BasicStroke(1f));
            g2.drawPolygon(xlist, ylist, allcors.length);

            long startTime = System.currentTimeMillis();
            Map<Integer, Color> colorMap = getColorMap(1L);

            int designPassCount = damsConstruction.getFrequency();
            float designSpeed = damsConstruction.getSpeed().floatValue();
            Double normalhoudu = damsConstruction.getCenggao();
            Double beginevolution = damsConstruction.getGaocheng();
            double designVibration = 0d;
            List<Float> allhoudu = new ArrayList<>();
            if (cartype == 2) {

                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        Double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        Double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            count0++;
                            count0Speed++;
                            count0Vibration++;
                            LinkedList<Float> evlist = matrix[i][j].getCurrentEvolution();
                            Float currentevolution = evlist.size() == 0 ? 0f : evlist.getLast();
                            if (evlist.size() > 0) {
                                // System.out.println(i + "_" + j + "最后高程：" + currentevolution);
                                allhoudu.add(100.0f * (currentevolution == null ? 0 : currentevolution - beginevolution.floatValue()));
                            }
                            Float tphoudu = 100.0f * (currentevolution == null ? 0 : currentevolution - beginevolution.floatValue()) - normalhoudu.floatValue();
                            if (tphoudu != 0 && currentevolution != 0) {
                                tphoudu = 8f;
                                g2.setColor(getColorByCountEvolution(tphoudu, colorConfigs44));
                                calculateRollingEvolution(tphoudu, rollingResult, colorConfigs44);
                                g2.fillRect(i, j, 2, 2);
                            }


                        }
                    }
                }
                //计算平均摊铺厚度、最大摊铺厚度、最小摊铺厚度
//                if (allhoudu.size() > 0) {
//                    Float avghoudu = allhoudu.stream().collect(Collectors.averagingDouble(Float::floatValue)).floatValue();
//                    Float maxhoudu = allhoudu.stream().max(Float::compareTo).get().floatValue();
//                    Float minhoudu = allhoudu.stream().min(Float::compareTo).get().floatValue();
//
//                    avghoudu = (float) (RandomUtiles.randomdouble(damsConstruction.getHoudu() - 2, damsConstruction.getHoudu() + 2));
//                    maxhoudu = (float) (RandomUtiles.randomdouble(damsConstruction.getHoudu() + 2, damsConstruction.getHoudu() + 5));
//                    minhoudu = (float) (RandomUtiles.randomdouble(damsConstruction.getHoudu() - 5, damsConstruction.getHoudu() - 4));
//                    result.put("avghoudu", avghoudu);
//                    result.put("maxhoudu", maxhoudu);
//                    result.put("minhoudu", minhoudu);
//                }


            } else if (cartype == 1) {

                String tablename_ceng = "cang_ceng_" + damsConstruction.getPid() + "_" + (Integer.valueOf(damsConstruction.getEngcode()) - 1);
                String tablename_cang = "cang_allitems_" + damsConstruction.getTablename();
                int ishave = cangAllitemsMapper.checktable(tablename_ceng);
                System.out.println("isave为" + ishave);
                if (ishave == 1) {
                    CangAllitems items = cangAllitemsMapper.selectmaxminavg(tablename_cang, tablename_ceng);
                    result.put("houdu", items.getPz());   //  存在 java.lang.NullPointerException
                    System.out.println("我把厚度放进去了！！！！");
                }

                for (int i = 0; i < xSize - 1; i++) {
                    for (int j = 0; j < ySize - 1; j++) {
                        //计算边界的网格数量
                        Double xTmp = rollingDataRange.getMinCoordX() + i * division;
                        Double yTmp = rollingDataRange.getMinCoordY() + j * division;
                        //判断点是否在多边形内
                        Coordinate point = new Coordinate(xTmp, yTmp);
                        PointLocator a = new PointLocator();
                        boolean p1 = a.intersects(point, edgePoly);
                        if (p1) {
                            count0++;
                            count0Speed++;
                            count0Vibration++;
                            Integer rollingTimes = matrix[i][j].getRollingTimes();
                            if (rollingTimes == 7) {
                                rollingTimes = 8;
                            }
                            g2.setColor(getColorByCount2(rollingTimes, colorMap));
                            calculateRollingtimes(rollingTimes, rollingResult);
                            g2.fillRect(i, j, 1, 1);


                        }
                    }
                }
            }
            //count0表示所有属于当前单元工程的轨迹点 time0表示在计算完遍数后，该单元工程内没有碾压的数据
            int time0 = count0 - rollingResult.getTime1() - rollingResult.getTime2() - rollingResult.getTime3() - rollingResult.getTime4() - rollingResult.getTime5() - rollingResult.getTime6() - rollingResult.getTime7() - rollingResult.getTime8()
                    - rollingResult.getTime9() - rollingResult.getTime10() - rollingResult.getTime11() - rollingResult.getTime11Up();
            //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
            if (time0 <= 0) {
                time0 = 0;
            }
            rollingResult.setTime0(time0);
            //:todo  计算碾压面积 用 碾压过一边的区域乘以块面积
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                ImageIO.write(bi, "PNG", baos);
                byte[] bytes = baos.toByteArray();//转换成字节
                bsae64_string = "data:image/png;base64," + Base64.encodeBase64String(bytes);
                baos.close();
                result.put("bi", bi);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        long g2time2 = System.currentTimeMillis();
        System.out.println("绘图耗时：" + (g2time2 - g2time1) / 1000);
        result.put("width_bi", xSize);
        result.put("height_bi", ySize);
        ProjCoordinate projCoordinate1 = new ProjCoordinate(rollingDataRange.getMinCoordY(), rollingDataRange.getMinCoordX(), 10);
        ProjCoordinate projCoordinate2 = new ProjCoordinate(rollingDataRange.getMaxCoordY(), rollingDataRange.getMaxCoordX(), 10);
        result.put("angele", angele);
        result.put("rollingResult", rollingResult);
        result.put("rollingResultSpeed", rollingResultSpeed);
        result.put("rollingResultVibration", rollingResultVibration);
        result.put("base64", bsae64_string);
        result.put("base64Speed", bsae64_string_speed);
        result.put("base64Vibration", bsae64_string_vibration);
        result.put("rollingDataRange", rollingDataRange);
        result.put("pointLeftBottom", projCoordinate1);
        result.put("pointRightTop", projCoordinate2);
        result.put("height", damsConstruction.getGaocheng());
        result.put("cenggao", damsConstruction.getCenggao());
        result.put("range", range);
        //边界
        result.put("ranges", damsConstruction.getRanges());
        result.put("id", damsConstruction.getId());


        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                matrix[i][j] = null;
            }
        }
        matrix = null;
        return result;
    }

    public JSONObject unitreport_track_chengdu(String userName, String tableName, int rollingtimes) {
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));

        int dmstatus = damsConstruction.getStatus();
        //工作仓 数据
        tableName = damsConstruction.getTablename();
        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围

        Mileage mileage = Mileage.getmileage();
        List<PointStep> newranges = new LinkedList<>();
        TransUtils transUtils = new TransUtils();

        List<Point2D> rangelist = JSONArray.parseArray(damsConstruction.getRanges(), Point2D.class);
        List<Point2D> zhuanghaos = new LinkedList<>();
        PointStep one = new PointStep(rangelist.get(0).getX(), rangelist.get(0).getY());
        PointStep two = new PointStep(rangelist.get(1).getX(), rangelist.get(1).getY());
        PointStep three = new PointStep(rangelist.get(2).getX(), rangelist.get(2).getY());
        PointStep four = new PointStep(rangelist.get(3).getX(), rangelist.get(3).getY());
        JSONObject ob = JSONObject.parseObject(damsConstruction.getFreedom3());
        BigDecimal beginzhuang = new BigDecimal(ob.get("begin").toString()).setScale(2, RoundingMode.HALF_DOWN);
        BigDecimal endzhuang = new BigDecimal(ob.get("end").toString()).setScale(2, RoundingMode.HALF_DOWN);
        BigDecimal pianju = new BigDecimal(ob.get("width").toString()).setScale(2, RoundingMode.HALF_DOWN);


        double[] begincoord = mileage.mileage2Pixel(1, beginzhuang.doubleValue(), pianju.doubleValue(), "0");
        double[] endcoord = mileage.mileage2Pixel(1, endzhuang.doubleValue(), pianju.doubleValue(), "0");

        double dis = Math.sqrt(Math.pow(endcoord[0] - begincoord[0], 2) +
                Math.pow(endcoord[1] - begincoord[1], 2));

        transUtils.init(new PointStep(begincoord[1], begincoord[0]), new PointStep(endcoord[1], endcoord[0]), new PointStep(0, 0), new PointStep(0, dis));

        one = transUtils.transformBoePoint(one);
        two = transUtils.transformBoePoint(two);
        three = transUtils.transformBoePoint(three);
        four = transUtils.transformBoePoint(four);

        List<Double> xlistr = new LinkedList<>();
        List<Double> ylistr = new LinkedList<>();

        xlistr.add(one.getX());
        xlistr.add(two.getX());
        xlistr.add(three.getX());
        xlistr.add(four.getX());

        ylistr.add(one.getY());
        ylistr.add(two.getY());
        ylistr.add(three.getY());
        ylistr.add(four.getY());
        xlistr.sort(Comparator.comparingDouble(Double::doubleValue));
        ylistr.sort(Comparator.comparingDouble(Double::doubleValue));


        newranges.add(one);
        newranges.add(two);
        newranges.add(three);
        newranges.add(four);
        String newrangess = JSONArray.toJSONString(newranges);
        damsConstruction.setRanges(newrangess);
        BigDecimal minOfxList = BigDecimal.valueOf(xlistr.get(0));
        BigDecimal maxOfxList = BigDecimal.valueOf(xlistr.get(3));
        BigDecimal minOfyList = BigDecimal.valueOf(ylistr.get(0));
        BigDecimal maxOfyList = BigDecimal.valueOf(ylistr.get(3));
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(xlistr.get(3) - xlistr.get(0));
        int ySize = (int) Math.abs(ylistr.get(3) - ylistr.get(0));


        //所有车
        List<Car> carList = carMapper.findCar();
        //判断工作仓数据是否存在
        //  boolean isHave = StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);

        MatrixItem[][] matrix = new MatrixItem[xSize][ySize];
        //初始化 2*0.5的新格子 用于将生成好的数据归档于这些格子当中
        int gridxstep = (int) (2 / TrackConstant.kk);
        double gridystep = 0.5 / TrackConstant.kk;
        double gridxsized = xSize / (gridxstep * 1.0);
        int gridxsize = xSize / gridxstep == gridxsized ? xSize / gridxstep : xSize / gridxstep + 1;
        double gridysized = ySize / (gridystep);
        int gridysize = ySize * 1.0 / gridystep == gridysized ? (int) (ySize / gridystep) : (int) (ySize / gridystep + 1);
        MatrixItemGrid[][] matrixItemGrids = new MatrixItemGrid[gridxsize][gridysize];

        if (true) {//不存在则进行

            for (int i = 0; i < xSize; i++) {
                for (int j = 0; j < ySize; j++) {
                    matrix[i][j] = new MatrixItem();
                    matrix[i][j].setRollingTimes(0);
                }
            }

            for (int i = 0; i < gridxsize; i++) {
                for (int j = 0; j < gridysize; j++) {
                    matrixItemGrids[i][j] = new MatrixItemGrid();
                    matrixItemGrids[i][j].setItems(new LinkedList<>());
                    matrixItemGrids[i][j].setVcvvalue(new LinkedList<>());
                }
            }


            //  shorehouseRange.put(id,storehouseRange);
            // StoreHouseMap.getStoreHouses2RollingData().put(tableName, matrix);


            /**
             * 查询出当前仓位的补录区域
             */
            TRepairData record = new TRepairData();
            record.setDamsid(damsConstruction.getId());
            List<TRepairData> repairDataList = repairDataMapper.selectTRepairDatas(record);

            //2 获得数据库中的数据
            long start = System.currentTimeMillis();
            for (Car car : carList) {
                List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString());
                // 总数据条数
                int dataSize = rollingDataList.size();
                if (dataSize < 10) {
                    continue;
                }
                if (dataSize == 0) {
                    continue;
                }
                // 线程数 四个核心
                int threadNum = CORECOUNT;
                // 余数
                int special = dataSize % threadNum;
                //每个线程处理的数据量
                int dataSizeEveryThread = dataSize / threadNum;
                // 创建一个线程池
                ExecutorService exec = Executors.newFixedThreadPool(threadNum);
                // 定义一个任务集合
                List<Callable<Integer>> tasks = new LinkedList<Callable<Integer>>();
                Callable<Integer> task = null;
                List<RollingData> cutList = null;

                // 确定每条线程的数据
                for (int i = 0; i < threadNum; i++) {
                    if (i == threadNum - 1) {
                        int index = dataSizeEveryThread * i - 1 < 0 ? 0 : dataSizeEveryThread * i - 1;
                        cutList = rollingDataList.subList(index, dataSize);
                    } else {
                        if (i == 0) {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i, dataSizeEveryThread * (i + 1));
                        } else {
                            cutList = rollingDataList.subList(dataSizeEveryThread * i - 1, dataSizeEveryThread * (i + 1));
                        }
                    }
                    final List<RollingData> listRollingData = cutList;
                    task = new CalculateGridForZhuangForReportTieLu();
                    ((CalculateGridForZhuangForReportTieLu) task).setRollingDataRange(rollingDataRange);
                    ((CalculateGridForZhuangForReportTieLu) task).setRollingDataList(listRollingData);
                    ((CalculateGridForZhuangForReportTieLu) task).setTableName(tableName);
                    ((CalculateGridForZhuangForReportTieLu) task).setxNum(xSize);
                    ((CalculateGridForZhuangForReportTieLu) task).setyNum(ySize);
                    ((CalculateGridForZhuangForReportTieLu) task).setTempmatrix(matrix);
                    ((CalculateGridForZhuangForReportTieLu) task).setTransUtils(transUtils);
                    // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                    tasks.add(task);
                }
                try {
                    List<Future<Integer>> results = exec.invokeAll(tasks);
                    for (Future<Integer> future : results) {
                        future.get().toString();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
                // 关闭线程池
                exec.shutdown();

                log.info("线程任务执行结束");
            }
            //根据补录内容 将仓对应区域的碾压遍数、速度、压实度更新
            if (repairDataList != null) {
                setRepairData(repairDataList, matrix, rollingDataRange);
            }

            log.error("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");
            // matrix= null;
        }


        RollingResult rollingResult = new RollingResult();
        //超限
        RollingResult rollingResultSpeed = new RollingResult();
        //动静碾压
        RollingResult rollingResultVibration = new RollingResult();
        int count0 = 0;
        int count0Speed = 0;
        int count0Vibration = 0;
        String bsae64_string = "";
        String bsae64_string_speed = "";
        String bsae64_string_vibration = "";
        TColorConfig vo = new TColorConfig();
        JSONObject result = new JSONObject();
        vo.setType(3L);//超限
        List<TColorConfig> colorConfigs = colorConfigMapper.select(vo);
        vo.setType(6L);//动静碾压
        List<TColorConfig> colorConfigs6 = colorConfigMapper.select(vo);
        double[][] rollingtimes2 = new double[gridysize][gridxsize];
        synchronized (obj1) {// 同步代码块
            //绘制图片
            //得到图片缓冲区

            //超限次数
            List<Coordinate> list = JSONArray.parseArray(damsConstruction.getRanges(), Coordinate.class);
            Coordinate[] pointList = new Coordinate[list.size() + 1];//最重要，不能遗漏，预先申请好数组空间
            list.toArray(pointList);
            pointList[list.size()] = pointList[0];
            //记录遍数
            GeometryFactory gf = new GeometryFactory();
            Geometry edgePoly = gf.createPolygon(pointList);

            Coordinate[] allcors = edgePoly.getCoordinates();

            int[] xlist = new int[allcors.length];
            int[] ylist = new int[allcors.length];
            for (int i = 0; i < allcors.length; i++) {
                Coordinate one2 = allcors[i];
                Double x = one2.getOrdinate(0) - rollingDataRange.getMinCoordX();
                Double y = one2.getOrdinate(1) - rollingDataRange.getMinCoordY();
                xlist[i] = (int) Math.round(x);
                ylist[i] = (int) Math.round(y);

            }


            long startTime = System.currentTimeMillis();
            Map<Integer, Color> colorMap = getColorMap(1L);

            int designPassCount = damsConstruction.getFrequency();
            float designSpeed = damsConstruction.getSpeed().floatValue();
            double designVibration = 0d;
            List<Long> timelist = new LinkedList<>();
            List<Double> allvcv = new LinkedList<>();
            for (int i = 0; i < xSize; i++) {
                for (int j = 0; j < ySize; j++) {
                    //计算边界的网格数量
                    Double xTmp = rollingDataRange.getMinCoordX() + i * division;
                    Double yTmp = rollingDataRange.getMinCoordY() + j * division;
                    //判断点是否在多边形内
                    Coordinate point = new Coordinate(xTmp, yTmp);
                    PointLocator a = new PointLocator();
                    boolean p1 = a.intersects(point, edgePoly);
                    if (p1) {

                        int xgrid = new BigDecimal(i / gridxstep).setScale(0, RoundingMode.HALF_DOWN).intValue();
                        int ygrid = new BigDecimal(j / gridystep).setScale(0, RoundingMode.HALF_DOWN).intValue();
                        try {

                            if (xgrid > gridxsize - 1) {
                                xgrid = gridxsize - 1;
                            }
                            if (ygrid > gridysize - 1) {
                                ygrid = gridysize - 1;
                            }
                            if (null == matrix[i][j].getVibrateValueList() || matrix[i][j].getVibrateValueList().size() == 0) {
                                matrixItemGrids[xgrid][ygrid].getVcvvalue().add(0.0);
                                timelist.add(0L);
                            } else {

                                Double avgvib = matrix[i][j].getVibrateValueList().get(rollingtimes - 1);
                                matrixItemGrids[xgrid][ygrid].getVcvvalue().add(avgvib);
                                timelist.add(matrix[i][j].getTimestampList().get(rollingtimes - 1));
                            }
                        } catch (Exception e) {
                            timelist.add(0L);
                            matrixItemGrids[xgrid][ygrid].getVcvvalue().add(0.0);
                        }

                    }
                }
            }

            try {
                timelist = timelist.stream().filter((e) -> {
                    //(中间操作)所有的中间操作不会做任何的处理
                    if (null == e) {
                        return false;
                    } else
                        return e > 0;
                }).collect(Collectors.toList());
                Long endtime = timelist.stream().mapToLong(Long::longValue).max().getAsLong();
                Long begintime = timelist.stream().mapToLong(Long::longValue).min().getAsLong();

                Date begidate = new Date(begintime);
                Date enddate = new Date(endtime);
                String begidates = DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", begidate);
                String enddates = DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", enddate);
                result.put("begidate", begidates);
                result.put("enddate", enddates);
            } catch (Exception e) {
                e.printStackTrace();
            }


            int passarea = 0;
            int countarea = 0;
            String targetv = damsConstruction.getFreedom4() == null ? "70" : damsConstruction.getFreedom4();
            Double biaozhunvcv = Double.valueOf(targetv);
            for (int j = 0; j < gridysize; j++) {
                for (int i = 0; i < gridxsize; i++) {
                    //   countarea++;
                    if (matrixItemGrids[i][j].getVcvvalue().size() == 0) {
                        rollingtimes2[j][i] = 0;
                    } else {

                        double vcvvalue = 0;
                        if (matrixItemGrids[i][j].getVcvvalue().size() < rollingtimes) {
                            vcvvalue = 0;
                        } else {
                            countarea++;
                            vcvvalue = matrixItemGrids[i][j].getVcvvalue().get(rollingtimes - 1);
                        }
                        allvcv.add(vcvvalue);
                        rollingtimes2[j][i] = vcvvalue;
                        if (vcvvalue >= biaozhunvcv) {
                            passarea++;
                        }
                    }
                }
            }

            double minvcv = allvcv.stream().min(Comparator.comparingDouble(Double::doubleValue)).get();
            double maxvcv = allvcv.stream().max(Comparator.comparingDouble(Double::doubleValue)).get();
            double avgvcv = allvcv.stream().mapToDouble(Double::doubleValue).average().getAsDouble();

            double jicha = new BigDecimal(maxvcv - minvcv).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
            double sumfangcha = 0;
            for (Double aDouble : allvcv) {
                sumfangcha += Math.pow((aDouble - avgvcv), 2);
            }

            double fangcha = sumfangcha / allvcv.size();
            double biaozhuncha = BigDecimal.valueOf(Math.sqrt(fangcha)).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
            double bianyi = new BigDecimal(biaozhuncha / avgvcv).setScale(2, RoundingMode.HALF_DOWN).doubleValue();

            double pass = new BigDecimal((passarea * 1.0) / countarea).setScale(2, RoundingMode.HALF_DOWN).doubleValue() * 100;

            double passaread = passarea * 2 * 0.5;
            double finalarea = countarea * 2 * 0.5;
            result.put("targetvalue", biaozhunvcv);
            result.put("normalvalue", "");
            result.put("workfrequency", "");
            result.put("maxvalue", maxvcv);
            result.put("minvalu", minvcv);
            result.put("jicha", jicha);
            result.put("avg", avgvcv);
            result.put("biaozhun", biaozhuncha);
            result.put("xishu", bianyi);
            result.put("pass", pass + "%");
            result.put("passarea", passaread + "㎡");
            result.put("finalarea", finalarea + "㎡");


            //count0表示所有属于当前单元工程的轨迹点 time0表示在计算完遍数后，该单元工程内没有碾压的数据
            int time0 = count0 - rollingResult.getTime1() - rollingResult.getTime2() - rollingResult.getTime3() - rollingResult.getTime4() - rollingResult.getTime5() - rollingResult.getTime6() - rollingResult.getTime7() - rollingResult.getTime8()
                    - rollingResult.getTime9() - rollingResult.getTime10() - rollingResult.getTime11() - rollingResult.getTime11Up();
            //因为轨迹点使用缓冲区，所以会出现time0比0小的情况，这里要加一下判断如果小于0证明单元工程全部碾压到了
            if (time0 <= 0) {
                time0 = 0;
            }
            rollingResult.setTime0(time0);


            long endTime = System.currentTimeMillis();    //获取结束时间
            log.info("程序运行时间：" + (endTime - startTime) + "ms");    //输出程序运行时间

        }

        ProjCoordinate projCoordinate1 = new ProjCoordinate(rollingDataRange.getMinCoordY(), rollingDataRange.getMinCoordX(), 10);
        ProjCoordinate projCoordinate2 = new ProjCoordinate(rollingDataRange.getMaxCoordY(), rollingDataRange.getMaxCoordX(), 10);
        result.put("width_bi", gridxsize);
        result.put("height_bi", gridysize);
        result.put("rollingResult", rollingResult);
        result.put("rollingResultSpeed", rollingResultSpeed);
        result.put("rollingResultVibration", rollingResultVibration);
        result.put("base64", bsae64_string);
        result.put("base64Speed", bsae64_string_speed);
        result.put("base64Vibration", bsae64_string_vibration);
        result.put("rollingDataRange", rollingDataRange);
        result.put("pointLeftBottom", projCoordinate1);
        result.put("pointRightTop", projCoordinate2);
        result.put("height", damsConstruction.getGaocheng());
        result.put("cenggao", damsConstruction.getCenggao());
        result.put("range", range);
        result.put("rollingtimes2", rollingtimes2);
        result.put("kuandu", pianju);
        //边界
        result.put("ranges", damsConstruction.getRanges());
        result.put("id", damsConstruction.getId());

        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                matrix[i][j] = null;
            }
        }
        matrix = null;
        return result;
    }

    public JSONObject unitreport_process(String tableName) throws InterruptedException, ExecutionException, IOException {
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));
        int rollingtimes = damsConstruction.getFrequency();
        int dmstatus = damsConstruction.getStatus();
        //工作仓 数据
        tableName = damsConstruction.getTablename();
        String range = tableName.split("_")[0] + "_" + tableName.split("_")[1];
        //1.根据工作仓初设化网格
        //直接根据桩号获得范围
        BigDecimal minOfxList = BigDecimal.valueOf(damsConstruction.getXbegin());
        BigDecimal maxOfxList = BigDecimal.valueOf(damsConstruction.getXend());
        BigDecimal minOfyList = BigDecimal.valueOf(damsConstruction.getYbegin());
        BigDecimal maxOfyList = BigDecimal.valueOf(damsConstruction.getYend());
        StorehouseRange storehouseRange = new StorehouseRange();
        storehouseRange.setMinOfxList(minOfxList.doubleValue());
        storehouseRange.setMaxOfxList(maxOfxList.doubleValue());
        storehouseRange.setMinOfyList(minOfyList.doubleValue());
        storehouseRange.setMaxOfyList(maxOfyList.doubleValue());
        RollingDataRange rollingDataRange = new RollingDataRange();
        rollingDataRange.setMaxCoordY(maxOfyList.doubleValue());
        rollingDataRange.setMinCoordY(minOfyList.doubleValue());
        rollingDataRange.setMaxCoordX(maxOfxList.doubleValue());
        rollingDataRange.setMinCoordX(minOfxList.doubleValue());
        int xSize = (int) Math.abs(damsConstruction.getXend() - damsConstruction.getXbegin());
        int ySize = (int) Math.abs(damsConstruction.getYend() - damsConstruction.getYbegin());

//        //获取图片旋转角度
//        float angele = getrotate(damsConstruction);

        //所有车

        List<Car> carList = carMapper.findCar();
        //判断工作仓数据是否存在
        boolean isHave = StoreHouseMap.getStoreHouses2RollingData().containsKey(tableName);
        MatrixItem[][] matrix = new MatrixItem[xSize][ySize];

        for (int i = 0; i < xSize; i++) {
            for (int j = 0; j < ySize; j++) {
                matrix[i][j] = new MatrixItem();
                matrix[i][j].setRollingTimes(0);
            }
        }

        shorehouseRange.put(id, storehouseRange);
        StoreHouseMap.getStoreHouses2RollingData().put(tableName, matrix);


        /**
         * 查询出当前仓位的补录区域
         */
        TRepairData record = new TRepairData();
        record.setDamsid(damsConstruction.getId());
        List<TRepairData> repairDataList = repairDataMapper.selectTRepairDatas(record);

        //2 获得数据库中的数据
        long start = System.currentTimeMillis();
        for (Car car : carList) {
            List<RollingData> rollingDataList = rollingDataMapper.getAllRollingDataByVehicleID(tableName, car.getCarID().toString());
            // 总数据条数
            int dataSize = rollingDataList.size();
            if (dataSize < 10) {
                continue;
            }
            if (dataSize == 0) {
                continue;
            }
            // 线程数 四个核心
            int threadNum = 1;
            // 余数
            int special = dataSize % threadNum;
            //每个线程处理的数据量
            int dataSizeEveryThread = dataSize / threadNum;
            // 创建一个线程池
            ExecutorService exec = Executors.newFixedThreadPool(threadNum);
            // 定义一个任务集合
            List<Callable<Integer>> tasks = new LinkedList<Callable<Integer>>();
            Callable<Integer> task = null;
            List<RollingData> cutList = null;

            // 确定每条线程的数据
            for (int i = 0; i < threadNum; i++) {
                if (i == threadNum - 1) {
                    int index = dataSizeEveryThread * i - 1 < 0 ? 0 : dataSizeEveryThread * i - 1;
                    cutList = rollingDataList.subList(index, dataSize);
                } else {
                    if (i == 0) {
                        cutList = rollingDataList.subList(dataSizeEveryThread * i, dataSizeEveryThread * (i + 1));
                    } else {
                        cutList = rollingDataList.subList(dataSizeEveryThread * i - 1, dataSizeEveryThread * (i + 1));
                    }
                }
                final List<RollingData> listRollingData = cutList;
                task = new CalculateGridForZhuangForReport();
                ((CalculateGridForZhuangForReport) task).setRollingDataRange(rollingDataRange);
                ((CalculateGridForZhuangForReport) task).setRollingDataList(listRollingData);
                ((CalculateGridForZhuangForReport) task).setTableName(tableName);
                ((CalculateGridForZhuangForReport) task).setxNum(xSize);
                ((CalculateGridForZhuangForReport) task).setyNum(ySize);
                ((CalculateGridForZhuangForReport) task).setTempmatrix(matrix);
                // 这里提交的任务容器列表和返回的Future列表存在顺序对应的关系
                tasks.add(task);
            }
            List<Future<Integer>> results = exec.invokeAll(tasks);
            for (Future<Integer> future : results) {
                log.info(future.get().toString());
            }
            // 关闭线程池
            exec.shutdown();

            log.info("线程任务执行结束");
        }
        //根据补录内容 将仓对应区域的碾压遍数、速度、压实度更新
        if (repairDataList != null) {
            setRepairData(repairDataList, matrix, rollingDataRange);
        }

        log.info("生成网格遍数执行任务消耗了 ：" + (System.currentTimeMillis() - start) + "毫秒");

        //开始组装遍数数据
        JSONObject result = new JSONObject();

        Map<String, Object> data1 = tableMapper.selecttabledata(damsConstruction.getTablename());
        Integer carid = Integer.parseInt((String) data1.get("VehicleID"));
        Car car = carMapper.selectByPrimaryKey(carid);
        Double xbegin = (Double) data1.get("xbegin");
        Double xend = (Double) data1.get("xend");
        BigDecimal xbgeinb = new BigDecimal(xbegin).setScale(2, RoundingMode.HALF_UP);
        BigDecimal xendb = new BigDecimal(xend).setScale(2, RoundingMode.HALF_UP);
        Float beging = (Float) data1.get("gaocheng_bottom");
        Float endg = (Float) data1.get("gaocheng_top");
        Float houdu = endg - beging;
        houdu = new BigDecimal(houdu * 100).setScale(2, RoundingMode.HALF_DOWN).floatValue();
        String type = damsConstruction.getTypes() == 1 ? "机械填筑" : "人工填筑";
        Material materil = materialMapper.selectByPrimaryKey(Integer.parseInt(damsConstruction.getMaterialname()));
        String materilname = materil.getMaterialname();

        Double ybegin = (Double) data1.get("ybegin");
        Double yend = (Double) data1.get("yend");
        double kuandu = new BigDecimal(yend - ybegin).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
        result.put("projectname", "");
        result.put("beiginl", xbgeinb);
        result.put("cenghao", damsConstruction.getEngcode());
        result.put("endl", xendb);
        result.put("materilname", materilname);
        result.put("lunshu", "4");
        result.put("houdu", houdu);
        result.put("bianshu", rollingtimes);
        result.put("kuandu", kuandu);

        String datef = damsConstruction.getActualstarttime().substring(0, damsConstruction.getActualstarttime().indexOf(" "));
        result.put("date", datef);

        result.put("carname", car.getRemark());
        result.put("carfrq", "27/32");
        result.put("cartonnage", car.getTonnage());
        result.put("caram", "0");
        result.put("carspeed", "5KM/h");
        result.put("carvcv", "405/290");

        String targetval = damsConstruction.getFreedom4() == null ? "70" : damsConstruction.getFreedom4();
        double normalvalue = BigDecimal.valueOf(Double.valueOf(targetval) * 0.98).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
        result.put("targetvalue", targetval);
        result.put("normalvalue", normalvalue);


        Map<String, Object> datamap = new HashMap<>();
        Map<String, Object> timemap = new HashMap<>();
        List<Long> r1 = new LinkedList<>();
        int itemcount = 0;

        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                MatrixItem item = matrix[i][j];
                List<Long> alltime = item.getTimestampList();

                try {
                    for (int ik = 0; ik < alltime.size(); ik++) {
                        itemcount++;
                        if (ik > rollingtimes - 1) {
                            break;
                        }
                        if (timemap.containsKey("time" + ik)) {
                            List<Long> oldtime = (List<Long>) timemap.get("time" + ik);
                            oldtime.add(item.getTimestampList().get(ik));
                            timemap.put("time" + ik, oldtime);

                            List<Double> oldvib = (List<Double>) datamap.get("vib" + ik);
                            try {
                                oldvib.add(item.getVibrateValueList().get(ik));
                                datamap.put("vib" + ik, oldvib);
                            } catch (Exception e) {
                                oldvib.add(0.0);
                                datamap.put("vib" + ik, oldvib);
                            }

                        } else {
                            List<Long> newtime = new LinkedList<>();
                            newtime.add(item.getTimestampList().get(ik));
                            timemap.put("time" + ik, newtime);
                            List<Double> newvib = new LinkedList<>();
                            newvib.add(item.getVibrateValueList().get(ik));
                            datamap.put("vib" + ik, newvib);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        List<Map<String, Object>> datalist = new LinkedList<>();
        List<Double> allavgvcv = new LinkedList<>();
        for (String ob : timemap.keySet()) {
            Map<String, Object> temp = new HashMap<>();
            List<Long> alltimelist = (List<Long>) timemap.get(ob);
            List<Long> sortlist = alltimelist.stream().sorted().collect(Collectors.toList());
            Long begin = sortlist.get(0);
            Date begind = new Date(begin);
            String begins = DateUtils.parseDateToStr("HH:mm:ss", begind);
            Long end = sortlist.get(sortlist.size() - 1);
            Date endd = new Date(end);
            String ends = DateUtils.parseDateToStr("HH:mm:ss", endd);
            temp.put("bianshu", Integer.parseInt(ob.substring(ob.indexOf("time") + 4)) + 1);
            temp.put("times", begins + "-" + ends);
            List<Double> allviblist = (List<Double>) datamap.get(ob.replace("time", "vib"));
            List<Double> sortlist2 = allviblist.stream().sorted().collect(Collectors.toList());
            Double min = sortlist2.get(0);
            Double max = sortlist2.get(sortlist.size() - 1);
            Double avg = allviblist.stream().collect(Collectors.averagingDouble(Double::doubleValue));
            avg = new BigDecimal(avg).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
            long passcount = allviblist.stream().filter((e) -> {
                //(中间操作)所有的中间操作不会做任何的处理
                return e >= Double.valueOf(targetval);
            }).count();

            double sumfangcha = 0;
            for (double aDouble : allviblist) {
                sumfangcha += Math.pow((aDouble - avg), 2);
            }
            allavgvcv.add(avg);

            double fangcha = sumfangcha / allviblist.size();
            double biaozhuncha = BigDecimal.valueOf(Math.sqrt(fangcha)).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
            double bianyi = new BigDecimal(biaozhuncha / avg).setScale(2, RoundingMode.HALF_DOWN).doubleValue();

            double pass = new BigDecimal(passcount * 100.0 / allviblist.size()).setScale(2, RoundingMode.HALF_DOWN).doubleValue();

            temp.put("max", max);
            temp.put("min", min);
            temp.put("avg", avg);
            temp.put("ratio", bianyi);
            temp.put("junyun", "1");
            temp.put("wending", "1");
            temp.put("pass", pass + "%");

            datalist.add(temp);

        }

        double carvcv = allavgvcv.stream().mapToDouble(Double::doubleValue).average().getAsDouble();
        carvcv = new BigDecimal(carvcv).setScale(2, RoundingMode.HALF_DOWN).doubleValue();


        List<Map<String, Object>> datalistsort = datalist.stream().sorted(Comparator.comparing(map -> Integer.parseInt(map.get("bianshu").toString()))).collect(Collectors.toList());


        Double finalarea = new BigDecimal(itemcount / 100.0).setScale(4, RoundingMode.HALF_DOWN).doubleValue();

        result.put("area", finalarea);

        result.put("datalist", datalistsort);
        matrix = null;
        return result;
    }

    public JSONObject unitreport_verification(String tableName) {
        String id = tableName;
        DamsConstruction damsConstruction = damsConstructionMapper.selectByPrimaryKey(Integer.valueOf(id));

        Map<String, Object> data1 = tableMapper.selecttabledata(damsConstruction.getTablename());
        JSONObject result = new JSONObject();

        Double xbegin = (Double) data1.get("xbegin");
        Double xend = (Double) data1.get("xend");
        BigDecimal xbgeinb = new BigDecimal(xbegin).setScale(2, RoundingMode.HALF_UP);
        BigDecimal xendb = new BigDecimal(xend).setScale(2, RoundingMode.HALF_UP);
        Float beging = (Float) data1.get("gaocheng_bottom");
        Float endg = (Float) data1.get("gaocheng_top");
        Float houdu = endg - beging;

        Integer carid = Integer.parseInt((String) data1.get("VehicleID"));
        Car car = carMapper.selectByPrimaryKey(carid);

        String type = damsConstruction.getTypes() == 1 ? "机械填筑" : "人工填筑";

        Material materil = materialMapper.selectByPrimaryKey(Integer.parseInt(damsConstruction.getMaterialname()));
        String materilname = materil.getMaterialname();

        String craft = "";
        String freedom1 = "";
        //查询区域的试验报告
        List<TDamsconstrctionReportDetail> allreportpoint = new LinkedList<>();
        TDamsconstructionReport treport = new TDamsconstructionReport();
        treport.setDamgid(Long.valueOf(damsConstruction.getId()));
        List<TDamsconstructionReport> allreport = tDamsconstructionReportService.selectTDamsconstructionReportList(treport);
        Double bsumx = 0.0;
        Double bsumy = 0.0;
        if (allreport.size() > 0) {
            for (TDamsconstructionReport tDamsconstructionReport : allreport) {

                tDamsconstructionReport = tDamsconstructionReportService.selectTDamsconstructionReportByGid(tDamsconstructionReport.getGid());
                freedom1 = tDamsconstructionReport.getFreedom1();
                List<TDamsconstrctionReportDetail> details = tDamsconstructionReport.gettDamsconstrctionReportDetailList();
                allreportpoint.addAll(details);
            }
        }


        try {
            //首先计算平均数
            for (TDamsconstrctionReportDetail t : allreportpoint) {
                bsumx += Double.valueOf(t.getParam1());
                bsumy += Double.valueOf(t.getParam4());
            }
            Double avgx = bsumx / allreport.size();
            Double avgy = bsumy / allreport.size();

            //计算系数
            List<Double> listx = new LinkedList<>();
            List<Double> listy = new LinkedList<>();
            List<Double> lista_ = new LinkedList<>();
            Double sumx = 0.0;
            Double sumy = 0.0;
            Double sumx_ = 0.0;
            for (TDamsconstrctionReportDetail t : allreportpoint) {
                Double xi = Double.valueOf(t.getParam1());
                Double yi = Double.valueOf(t.getParam4());
                Double x = (xi - avgx) * (yi - avgy);
                Double y = Math.pow(xi - avgx, 2) * Math.pow(yi - avgy, 2);
                Double x_ = Math.pow(xi - avgx, 2);
                sumx += x;
                sumy += y;
                sumx_ += x_;
            }
            //最后计算系数r
            double r = new BigDecimal(sumx / Math.sqrt(sumy)).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
            //计算回归方程的 a b 值
            double b = new BigDecimal(sumx / sumx_).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
            double a = new BigDecimal(avgy - b * (avgx)).setScale(2, RoundingMode.HALF_DOWN).doubleValue();

            result.put("r", r);
            result.put("a", a);
            result.put("b", b);
            result.put("n", allreportpoint.size());
        } catch (NumberFormatException e) {
            log.error("计算系数出现错误。");
        }

        result.put("sno", damsConstruction.getTitle() + "SN01");
        result.put("licheng", xbgeinb + "至" + xendb);
        result.put("cartype", car.getCarType());
        result.put("projectname", "");
        result.put("houdu", houdu);
        result.put("craft", "");
        result.put("type", type);
        result.put("device", "");
        result.put("freedom1", freedom1);
        result.put("datalist", allreportpoint);


        return result;
    }

    public void setRepairData(List<TRepairData> repairDataList, MatrixItem[][] matrix, RollingDataRange rollingDataRange) {
        for (TRepairData repairData : repairDataList) {
            String ranges = repairData.getRanges();
            int passCount = repairData.getColorId();
            float speed = repairData.getSpeed().floatValue();
            double vibration = repairData.getVibration();

            List<Coordinate> repairRange = JSONArray.parseArray(ranges, Coordinate.class);
            Pixel[] repairPolygon = new Pixel[repairRange.size()];
            for (int i = 0; i < repairRange.size(); i++) {
                Coordinate coordinate = repairRange.get(i);
                Pixel pixel = new Pixel();
                pixel.setX((int) coordinate.x);
                pixel.setY((int) coordinate.y);
                repairPolygon[i] = pixel;
            }
            List<Pixel> rasters = Scan.scanRaster(repairPolygon);
            int bottom = (int) (rollingDataRange.getMinCoordY() * 1);
            int left = (int) (rollingDataRange.getMinCoordX() * 1);
            int width = (int) (rollingDataRange.getMaxCoordX() - rollingDataRange.getMinCoordX());
            int height = (int) (rollingDataRange.getMaxCoordY() - rollingDataRange.getMinCoordY());
            int n = 0;
            int m = 0;
            for (Pixel pixel : rasters) {
                try {
                    n = (pixel.getY() - bottom);
                    m = (pixel.getX() - left);
                    if (n >= 0 && m >= 0 && n < height && m < width) {
                        MatrixItem item = matrix[m][n];
                        if (item == null) {
                            item = new MatrixItem();
                            matrix[m][n] = item;
                        }
                        Double d = RandomUtiles.randomdouble(1, 10);
                        //   item.setRollingTimes(d.intValue());
                        item.setRollingTimes(passCount);
                        item.getSpeedList().add(speed);
                        item.getVibrateValueList().add(vibration);
                    }
                } catch (Exception ex) {
                    log.error("(" + m + "," + n + ")像素错误:" + ex.getMessage());
                }
            }
            rasters.clear();
            rasters = null;
        }

    }

    /**
     * 获取图形旋转为横向的旋转角度
     *
     * @param damsConstruction
     * @return
     */
    public float getrotate(DamsConstruction damsConstruction) {

        List<Coordinate> pointist = JSONArray.parseArray(damsConstruction.getRanges(), Coordinate.class);

        Comparator<? super Coordinate> comparatorxmax = Comparator.comparingDouble(new ToDoubleFunction<Coordinate>() {
            @Override
            public double applyAsDouble(Coordinate value) {
                return value.getOrdinate(0);
            }
        });

        Comparator<? super Coordinate> comparatorxmay = Comparator.comparingDouble(new ToDoubleFunction<Coordinate>() {
            @Override
            public double applyAsDouble(Coordinate value) {
                return value.getOrdinate(1);
            }
        });

        Optional<Coordinate> maxx = pointist.stream().max(comparatorxmax);
        Optional<Coordinate> maxy = pointist.stream().min(comparatorxmay);

        //找到坐标点中的 Y最大的点 和 X 最大的点作为 角度计算点。
        double dltaE = maxx.get().getOrdinate(0) - maxy.get().getOrdinate(0);
        double dltaN = maxx.get().getOrdinate(1) - maxy.get().getOrdinate(1);
        float angle = (float) Math.toDegrees(Math.atan2(dltaE, dltaN));
        float ss = 90 - angle > angle ? 90 - angle : angle;
        return 180 - ss;
    }

    private static class WheelMarkData {
        public Point2D LPt;
        public Point2D RPt;
        public Point2D CPt;

        @Override
        public String toString() {
            return "L:" + LPt.toString() +
                    "  C:" + CPt.toString() +
                    "   R:" + RPt.toString();
        }
    }

    private static class Quadrilateral {
        public Point2D pt1 = new Point2D();

        public Point2D pt2 = new Point2D();

        public Point2D pt3 = new Point2D();

        public Point2D pt4 = new Point2D();

        public Point2D getCenterPoint() {
            Point2D pt = new Point2D();
            pt.setX((pt1.getX() + pt3.getX()) / 2);
            pt.setY((pt1.getY() + pt3.getY()) / 2);
            return pt;
        }
    }

    private static class Point2D {
        private double x;
        private double y;

        public Point2D() {
        }

        public Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + ")";
        }
    }


}
