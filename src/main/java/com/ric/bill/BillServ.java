package com.ric.bill;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ric.bill.excp.ErrorWhileChrg;
import com.ric.bill.excp.ErrorWhileDist;
import com.ric.bill.mm.HouseMng;
import com.ric.bill.mm.KartMng;
import com.ric.bill.mm.ObjMng;
import com.ric.bill.mm.ParMng;
import com.ric.bill.model.ar.House;
import com.ric.bill.model.ar.Kart;
import com.ric.bill.model.mt.MLogs;
import com.ric.bill.model.mt.MeterLog;

/**
 * Главный сервис биллинга
 * 
 * @author lev
 *
 */
@Service
@Scope("prototype") // странно что стоит prototype, а вызвается как Autowired в BillingController - поменял это, но проверить! TODO!  
@Slf4j
public class BillServ {

	@Autowired
	private KartMng kartMng;
	@Autowired
	private ApplicationContext ctx;
	@PersistenceContext
	private EntityManager em;
	@Autowired
	private HouseMng houseMng;

	// коллекция для формирования потоков
	private List<ResultSet> kartThr;

	// конструктор
	public BillServ() {

	}

	// получить список N следующих лиц.счетов, для расчета в потоках
	private List<ResultSet> getNextKart(int cnt) {
		List<ResultSet> lst = new ArrayList<ResultSet>();
		int i = 1;
		Iterator<ResultSet> itr = kartThr.iterator();
		while (itr.hasNext()) {
			ResultSet rs = itr.next();
			lst.add(rs);
			itr.remove();
			i++;
			if (i > cnt) {
				break;
			}
		}

		return lst;
	}

	// Exception из потока
	Thread.UncaughtExceptionHandler expThr = new Thread.UncaughtExceptionHandler() {
		public void uncaughtException(Thread th, Throwable ex) {
			System.out.println("BillServ: Error in thread: " + th.getName()
					+ " " + ex.getMessage());
			ex.printStackTrace();
		}
	};

	/**
	 * выполнить распределение объемов и начисление по всем домам в потоках
	 * 
	 * @param isDist
	 *            - распределять ли объемы
	 * @param isChrg
	 *            - выполнять ли начисление
	 * @return
	 * @throws ExecutionException 
	 * @throws InterruptedException 
	 */
	@Async
	@CacheEvict(value = {"TarifMngImpl.getOrg", "KartMngImpl.getOrg", "KartMngImpl.getServ", "KartMngImpl.getServAll", 
			"KartMngImpl.getCapPrivs", "KartMngImpl.getServPropByCD", "KartMngImpl.getStandartVol", "KartMngImpl.getCntPers", "KartMngImpl.checkPersNullStatus",
			"KartMngImpl.checkPersStatusExt", "KartMngImpl.checkPersStatus", "ObjDAOImpl.getByCD", "MeterLogDAOImpl.getKart", "OrgDAOImpl.getByKlsk", "ParDAOImpl.getByCd",
			"ParDAOImpl.checkPar", "ServDAOImpl.findMain", "ServDAOImpl.getByCD", "DistGen.findLstCheck", "MeterLogMngImpl.getAllMetLogByServTp", "MeterLogMngImpl.checkExsKartMet",
			"MeterLogMngImpl.checkExsMet", "MeterLogMngImpl.getVolPeriod1", "MeterLogMngImpl.getVolPeriod2", "MeterLogMngImpl.getLinkedNode", 
			"MeterLogMngImpl.getKart", "ParMngImpl.isExByCd", "ParMngImpl.getBool1", "ParMngImpl.getBool2", "ParMngImpl.getDbl1", "ParMngImpl.getDbl2", "ParMngImpl.getDate",
			"ParMngImpl.getStr1", "ParMngImpl.getStr2", "TarifMngImpl.getProp", "TarifDAOImpl.getPropByCD", "VsecDAOImpl.getPrivByUserRoleAct", "LstMngImpl.getByCD",
			"ServMngImpl.getUpper", "ServMngImpl.getUpperTree", "MeterLogMngImpl.delNodeVol",
			"rrr1" }, allEntries = true)
	public Future<Result> chrgAll(RequestConfig reqConfig, Integer houseId, Integer areaId, Integer tempLskId) throws InterruptedException, ExecutionException {
		// Logger.getLogger("org.hibernate.SQL").setLevel(Level.DEBUG);
		// Logger.getLogger("org.hibernate.type").setLevel(Level.TRACE);
		Result res = new Result();
		res.setErr(0);
		AsyncResult<Result> futM = new AsyncResult<Result>(res);
		
		// кол-во потоков
		int cntThreads = 20;
		// кол-во обраб.лиц.сч.
		int cntLsk = 0;

		long startTime;
		long endTime;
		long totalTime;
		long totalTime3;

		startTime = System.currentTimeMillis();

		// РАСПРЕДЕЛЕНИЕ ОБЪЕМОВ, если задано
		try {
			if (reqConfig.getIsDist()) {
				Calc calc = new Calc(reqConfig);
					distAll(calc, houseId, areaId, tempLskId);
				log.info("BillServ.chrgAll: Распределение по всем домам выполнено!");
			}
		} catch (ErrorWhileDist e) {
			log.error("Ошибка при распределении объемов по дому house.id={}", houseId);
			res.setErr(1);
		}
		
		// РАСЧЕТ НАЧИСЛЕНИЯ ПО ЛС В ПОТОКАХ
		if (res.getErr() ==0) {
			long startTime3 = System.currentTimeMillis();
			// загрузить все необходимые Лиц.счета
			kartThr = kartMng.findAllLsk(houseId, areaId, tempLskId, reqConfig.getCurDt1(), reqConfig.getCurDt2());

			//kartThr = kartMng.findAll(houseId, areaId, tempLskId, reqConfig.getCurDt1(), reqConfig.getCurDt2());
			cntLsk = kartThr.size();
			
			while (true) {
				//log.trace("BillServ.chrgAll: Loading karts for threads");
				// получить следующие N лиц.счетов, рассчитать их в потоке
				long startTime2;
				long endTime2;
				long totalTime2;
				startTime2 = System.currentTimeMillis();
	
				List<ResultSet> kartWork = getNextKart(cntThreads);
				if (kartWork.isEmpty()) {
					// выйти, если все лс обработаны
					break;
				}
	
				List<Future<Result>> frl = new ArrayList<Future<Result>>();
				List<ChrgServThr> thrl = new ArrayList<ChrgServThr>(cntThreads);
				//log.info("========================================== Запуск на выполнение потоков ===");
				for (ResultSet t : kartWork) {
	
					Future<Result> fut = null;
					ChrgServThr chrgServThr = ctx.getBean(ChrgServThr.class);
					
					thrl.add(chrgServThr);
					
					try {
						fut = chrgServThr.chrgAndSaveLsk(reqConfig, t.getId());
					} catch (ErrorWhileChrg | ExecutionException e) {
						// TODO Auto-generated catch block
						//e.printStackTrace();
						log.info("====================== НЕ ОБРАБОТАННАЯ ОШИБКА!!!!!!!!!!!!!!!");
					}
					frl.add(fut);
					log.info("Начат поток для лс={}", t.getId());
				}
				kartWork = null;
				
				// проверить окончание всех потоков
				int flag2 = 0;
				while (flag2 == 0) {
					//log.info("========================================== Ожидание выполнения потоков ===========");
					flag2 = 1;
					for (Future<Result> fut : frl) { 
						//log.info("========= 1");
						
						if (!fut.isDone()) {
							// не завершен поток
							//log.info("========= Поток НЕ завершен! лс={}", fut.get().getLsk());
							flag2 = 0;
						} else {
							try {
								//log.info("Поток по лс={} завершен с результатом: Result.err:={}", fut.get().getLsk(), fut.get().getErr());
								if (fut.get().getErr() == 1) {
								  log.error("ОШИБКА ПОЛУЧЕНА ПОСЛЕ ЗАВЕРШЕНИЯ ПОТОКА!");
								}
							} catch (InterruptedException | ExecutionException e1) {
								// TODO Auto-generated catch block
								e1.printStackTrace();
								log.error("ОШИБКА ВО ВРЕМЯ ВЫПОЛНЕНИЯ ПОТОКА!", e1);
							}
	
						}
					}
	
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					//log.info("========= 2");

				}
				for (ChrgServThr a :thrl) {
					a = null;
				}
				
				frl = null;
				thrl = null;

				endTime2 = System.currentTimeMillis();
				totalTime2 = endTime2 - startTime2;
				log.info("Промежуточное время выполнения одного лс:" + totalTime2 / cntThreads, 2);
	
			}
			endTime = System.currentTimeMillis();
			totalTime = endTime - startTime;
			totalTime3 = endTime - startTime3;
			log.info("Рассчитано лицевых в данной areaId={}, cnt={}", areaId, cntLsk);
			log.info("Общее время выполнения в данной areaId={}", areaId, totalTime);
			if (cntLsk > 0) {
				log.info("Итоговое время выполнения одного в данной areaId={}, cnt={}, мс.", areaId, totalTime3 / cntLsk);
			}
		}
		return futM;
	}

	
	/**
	 * выполнить начисление по лиц.счету
	 * 
	 * @param kart
	 *            - лиц счет (заполнен либо он, либо lsk)
	 * @param lsk
	 *            - номер лиц.счета
	 * @param dist
	 *            - распределить объемы?
	 * @throws InterruptedException
	 * @throws ErrorWhileChrg
	 */
	@Async
	@CacheEvict(value = {"TarifMngImpl.getOrg", "KartMngImpl.getOrg", "KartMngImpl.getServ", "KartMngImpl.getServAll", 
			"KartMngImpl.getCapPrivs", "KartMngImpl.getServPropByCD", "KartMngImpl.getStandartVol", "KartMngImpl.getCntPers", "KartMngImpl.checkPersNullStatus",
			"KartMngImpl.checkPersStatusExt", "KartMngImpl.checkPersStatus", "ObjDAOImpl.getByCD", "MeterLogDAOImpl.getKart", "OrgDAOImpl.getByKlsk", "ParDAOImpl.getByCd",
			"ParDAOImpl.checkPar", "ServDAOImpl.findMain", "ServDAOImpl.getByCD", "DistGen.findLstCheck", "MeterLogMngImpl.getAllMetLogByServTp", "MeterLogMngImpl.checkExsKartMet",
			"MeterLogMngImpl.checkExsMet", "MeterLogMngImpl.getVolPeriod1", "MeterLogMngImpl.getVolPeriod2", "MeterLogMngImpl.getLinkedNode", 
			"MeterLogMngImpl.getKart", "ParMngImpl.isExByCd", "ParMngImpl.getBool1", "ParMngImpl.getBool2", "ParMngImpl.getDbl1", "ParMngImpl.getDbl2", "ParMngImpl.getDate",
			"ParMngImpl.getStr1", "ParMngImpl.getStr2", "TarifMngImpl.getProp", "TarifDAOImpl.getPropByCD", "VsecDAOImpl.getPrivByUserRoleAct", "LstMngImpl.getByCD",
			"ServMngImpl.getUpper", "ServMngImpl.getUpperTree", "MeterLogMngImpl.delNodeVol",
			"rrr1" }, allEntries = true)
	public Future<Result> chrgLsk(RequestConfig reqConfig, Kart kart,
			Integer lsk) {
		Result res = new Result();
		Future<Result> fut = new AsyncResult<Result>(res);
		
		ChrgServThr chrgServThr = ctx.getBean(ChrgServThr.class);
		DistServ distServ = ctx.getBean(DistServ.class);

		// признак распределения по дому (в случае перерасчета по Отоплению)
		boolean isDistHouse = false;

		res.setErr(0);
		// Если был передан идентификатор лицевого, то найти лиц.счет
		if (lsk != null) {
			kart = em.find(Kart.class, lsk);
			if (kart == null) {
				log.error("УКАЗАННЫЙ ЛИЦЕВОЙ СЧЕТ={} НЕ НАЙДЕН!", lsk);
				res.setErr(1);
				return fut;
			}
		}
		Calc calc = new Calc(reqConfig);

		// установить дом и счет
		calc.setHouse(kart.getKw().getHouse());
		calc.setKart(kart);

		// установить признак распределения объема по дому, если перерасчет по Отоплению
		if (reqConfig.getOperTp().equals(1) && !reqConfig.getChng().getTp().getCd().equals("Изменение расценки (тарифа)") // если не пересчет расценки
				) { // кроме изменения расценки
			List<String> lstServCd = reqConfig.getChng().getChngLsk().stream()
					.filter(t -> t.getKart().getLsk().equals(lsk))
					.filter(t -> t.getServ() != null)
					.map(t-> t.getServ().getCd()).collect(Collectors.toList());
			if (lstServCd.contains("Отопление") || lstServCd.contains("Отопление(объем), соц.н.") || 
					lstServCd.contains("Отопление(объем), св.соц.н.") || lstServCd.contains("Отопление(объем), без прожив.")) {
				isDistHouse = true;
			}
		}
		// РАСПРЕДЕЛЕНИЕ ОБЪЕМОВ, если задано
		try {
			if (isDistHouse == true) {
				// задано распределить по дому, для перерасчета (например по отоплению, когда поменялась площадь и надо пересчитать гКал)
				distAll(calc, calc.getHouse().getId(), null, null);
				// присвоить обратно лиц.счет, который мог быть занулён в предыдущ методах
				calc.setKart(kart);
			} else if (reqConfig.getIsDist()) {
				distServ.distKartVol(calc);
				// присвоить обратно лиц.счет, который мог быть занулён в предыдущ методах
				calc.setKart(kart);
			}
		} catch (ErrorWhileDist e) {
			e.printStackTrace();
			res.setErr(1);
			return fut;
		}

		// РАСЧЕТ НАЧИСЛЕНИЯ ПО 1 ЛС
		try {
			fut = chrgServThr.chrgAndSaveLsk(reqConfig, kart.getLsk());
		} catch (ErrorWhileChrg | ExecutionException e) {
			e.printStackTrace();
			res.setErr(1);
			return fut;
		}

		return fut;
	}

	
	private void distAll(Calc calc, Integer houseId, Integer areaId, Integer tempLskId) throws ErrorWhileDist {
		int rqn = calc.getReqConfig().getRqn();
		long startTime;
		long endTime;
		long totalTime;
		for (House o : houseMng.findAll2(houseId, areaId, tempLskId, calc.getReqConfig().getCurDt1(), calc.getReqConfig().getCurDt2())) {
			log.info("RQN={}, Распределение объемов по House.id={}", calc.getReqConfig().getRqn(), o.getId());
			// распределить объемы
			startTime = System.currentTimeMillis();

			DistServ distServ = ctx.getBean(DistServ.class);
			// распределение объемов по дому
			distServ.distHouseVol(calc, rqn, o.getId());
			endTime = System.currentTimeMillis();
			totalTime = endTime - startTime;
			log.info("RQN={}, House.id={}, Время распределения: {}", calc.getReqConfig().getRqn(), o.getId(),
					totalTime);
		}
	}
	
	/**
	 * ТЕСТ-вызов не удалять!
	 * @param id
	 * @return
	 */
	@Async
	public Future<Result> chrgTest(Integer id) {
		Result res = new Result();
		Future<Result> fut = new AsyncResult<Result>(res);
		DistServ distServ = ctx.getBean(DistServ.class);
		distServ.distTest(id);
		log.info("======chrgTest");

		return fut;
	}
}
