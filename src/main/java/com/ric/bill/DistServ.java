package com.ric.bill;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ric.bill.dao.MeterDAO;
import com.ric.bill.dao.ServDAO;
import com.ric.bill.dto.MeterDTO;
import com.ric.bill.excp.CyclicMeter;
import com.ric.bill.excp.EmptyPar;
import com.ric.bill.excp.EmptyServ;
import com.ric.bill.excp.EmptyStorable;
import com.ric.bill.excp.ErrorWhileDist;
import com.ric.bill.excp.NotFoundNode;
import com.ric.bill.excp.NotFoundODNLimit;
import com.ric.bill.excp.WrongGetMethod;
import com.ric.bill.excp.WrongValue;
import com.ric.bill.mm.HouseMng;
import com.ric.bill.mm.KartMng;
import com.ric.bill.mm.LstMng;
import com.ric.bill.mm.MeterLogMng;
import com.ric.bill.mm.ParMng;
import com.ric.bill.mm.ServMng;
import com.ric.bill.mm.impl.MeterLogMngImpl;
import com.ric.bill.mm.impl.MeterLogMngImpl.AvgVol;
import com.ric.bill.model.ar.House;
import com.ric.bill.model.ar.Kart;
import com.ric.bill.model.bs.Lst;
import com.ric.bill.model.fn.Chng;
import com.ric.bill.model.mt.MLogs;
import com.ric.bill.model.mt.Meter;
import com.ric.bill.model.mt.MeterLog;
import com.ric.bill.model.mt.Vol;
import com.ric.bill.model.tr.Serv;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Сервис распределения объемов
 * @version 1.0 
 * @author Lev
 *
 */
@Service
@Scope("prototype")
@Slf4j
public class DistServ {

	@Autowired
	private Config config;
	@Autowired
	private MeterLogMng metMng;
	@Autowired
	private KartMng kartMng;
	@Autowired
	private MeterDAO meterDao;
	@Autowired
	private ParMng parMng;
	@Autowired
	private LstMng lstMng;
	@Autowired
	private ServMng servMng;
	@Autowired
	private ServDAO servDao;
	@Autowired
	private ApplicationContext ctx;

	//@Autowired // -здесь не надо autowire, так как prototype
	private DistGen distGen;

	// EntityManager - EM нужен на каждый DAO или сервис свой!
	@PersistenceContext
	private EntityManager em;

	//private Calc calc;
	/**
	 * Установить фильтры для сессии -убрал пока
	 * 
	 */
	/*
	 * private void setFilters() { Session session = em.unwrap(Session.class);
	 * //log.trace("Установлен фильтр: c:"+Calc.getCurDt1()+" по:"+Calc.getCurDt2());
	 * session.enableFilter("FILTER_GEN_DT_OUTER").setParameter("DT1",
	 * Calc.getCurDt1()) .setParameter("DT2", Calc.getCurDt2()); //отдельно
	 * установить фильтр существования счетчиков }
	 */

	/**
	 * Удалить объем по вводам дома
	 * 
	 * @param serv
	 *            - заданная услуга
	 * @throws CyclicMeter
	 */
	private void delHouseVolServ(int rqn, Calc calc) throws CyclicMeter {

		log.info("RQN={}, Удаление объемов по House.id={}  по услуге cd={}", rqn, calc.getHouse().getId(),
				calc.getServ().getCd(), 2);

		delHouseServVolTp(rqn, calc.getServ().getServMet(), 1, calc);
		delHouseServVolTp(rqn, calc.getServ().getServMet(), 0, calc);
		delHouseServVolTp(rqn, calc.getServ().getServMet(), 2, calc);
		delHouseServVolTp(rqn, calc.getServ().getServMet(), 3, calc);
		if (calc.getServ().getServOdn() != null) {
			delHouseServVolTp(rqn, calc.getServ().getServOdn(), 0, calc);// счетчики ОДН
		}
	}

	/**
	 * Удалить объем по вводу, определённой услуге
	 * 
	 * @param serv - услуга
	 * @throws CyclicMeter
	 */
	private void delHouseServVolTp(int rqn, Serv serv, int tp, Calc calc) throws CyclicMeter {
		// перебрать все необходимые даты, за период
		// необходимый для формирования диапазон дат
		Date dt1, dt2;
		if (calc.getCalcTp() == 2) {
			// формирование по ОДН - задать последнюю дату
			dt1 = calc.getReqConfig().getCurDt2();
			dt2 = calc.getReqConfig().getCurDt2();
		} else {
			// прочее формирование
			dt1 = calc.getReqConfig().getCurDt1();
			dt2 = calc.getReqConfig().getCurDt2();
		}

		// удалить объемы по всем вводам по дому и по услуге
		for (MLogs ml : metMng.getAllMetLogByServTp(rqn, calc.getHouse(), serv, "Ввод")) {
			//log.info("delNodeVol: house.id={}, mLog.id={}", calc.getHouse().getId(), ml.getId());
			
			metMng.delNodeVol(rqn, calc.getReqConfig().getChng()==null ? null : calc.getReqConfig().getChng().getId(),
					calc.getReqConfig().getChng(), ml, tp, calc.getReqConfig().getCurDt1(), calc.getReqConfig().getCurDt2()
							);
		}

	}

	/**
	 * распределить объем по всем услугам, по лиц.счету Как правило вызывается из
	 * начисления, поэтому не нуждается в блокировке лиц.счета
	 * 
	 * @throws ErrorWhileDist
	 */
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	public void distKartVol(Calc calc) throws ErrorWhileDist {
		Integer lsk = calc.getKart().getLsk();
		Integer houseId = calc.getKart().getKw().getHouse().getId();
		// блокировка лиц.счета
		int waitTick = 0;
		while (!config.lock.setLockChrgLsk(calc.getReqConfig().getRqn(), lsk, houseId)) {
			waitTick++;
			if (waitTick > 60) {
				log.error("********ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!");
				log.error("********НЕВОЗМОЖНО РАЗБЛОКИРОВАТЬ к lsk={} В ТЕЧЕНИИ 60 сек!{}", lsk);
				throw new ErrorWhileDist("Ошибка при блокировке лс lsk=" + lsk);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
				throw new ErrorWhileDist("Ошибка при блокировке лс lsk=" + lsk);
			}
		}

		try {
			//this.calc = calc;
			int rqn = calc.getReqConfig().getRqn();

			Kart kart = em.find(Kart.class, calc.getKart().getLsk());
			// почистить коллекцию обработанных счетчиков
			distGen = ctx.getBean(DistGen.class);
			distGen.clearLstChecks();

			// найти все необходимые услуги для удаления объемов, здесь только по типу 0,1,2
			// и только те услуги, которые надо удалить для ЛС
			try {
				for (Serv serv : servMng.getForDistVol()) {
					Boolean v = parMng.getBool(rqn, serv, "Распределять объем по только по дому");
					if (v == null || !v) {
						// Если задано, распределять услугу, только при выборе дома (не ЛС!)
						//log.trace("Удаление объема по услуге" + serv.getCd());
						// тип обработки = 0 - расход
						calc.setCalcTp(0);
						delKartServVolTp(rqn, kart, serv, calc);
						// тип обработки = 1 - площадь и кол-во прож.
						calc.setCalcTp(1);
						delKartServVolTp(rqn, kart, serv, calc);
						// тип обработки = 3 - пропорц.площади (отопление)
						calc.setCalcTp(3);
						delKartServVolTp(rqn, kart, serv, calc);
					}
				}
			} catch (CyclicMeter | EmptyStorable e) {
				e.printStackTrace();
				throw new ErrorWhileDist("Ошибка при удалении объемов счетчиков в лс=" + calc.getKart().getLsk());
			}

			//log.trace("Распределение объемов");
			// найти все необходимые услуги для распределения

			try {
				for (Serv serv : servMng.getForDistVol()) {
					Boolean v = parMng.getBool(rqn, serv, "Распределять объем по только по дому");
					if (v == null || !v) {
						// Если задано, распределять услугу, только при выборе дома (не ЛС!)
						//log.trace("Распределение услуги: " + serv.getCd());
						calc.setCalcTp(0);
						distKartServTp(rqn, kart, serv, calc);
						if (serv.getCd().equals("Отопление")) {
							// тип обработки = 1 - площадь и кол-во прож.
							calc.setCalcTp(1);
							distKartServTp(rqn, kart, serv, calc);
							// тип обработки = 3 - пропорц.площади (отопление)
							calc.setCalcTp(3);
							distKartServTp(rqn, kart, serv, calc);
						}
					}
				}
			} catch (ErrorWhileDist | EmptyStorable e) {
				e.printStackTrace();
				throw new ErrorWhileDist("Ошибка при распределении объемов счетчиков в лс=" + calc.getKart().getLsk());
			}
		} finally {
			// разблокировать лс
			config.lock.unlockChrgLsk(calc.getReqConfig().getRqn(), lsk, houseId);
		}
	}

	/**
	 * Удалить объем по Лиц.счету, определённой услуге
	 * 
	 * @param Kart
	 *            - лиц.счет
	 * @param serv
	 *            - услуга
	 * @param tp
	 *            - тип расчета
	 * @throws CyclicMeter
	 */
	private void delKartServVolTp(int rqn, Kart kart, Serv serv, Calc calc) throws CyclicMeter {
		//log.trace("delKartServVolTp: kart.lsk=" + kart.getLsk() + ", serv.cd=" + serv.getCd() + " tp="
		//		+ calc.getCalcTp());
		// перебрать все необходимые даты, за период
		Calendar c = Calendar.getInstance();
		// необходимый для формирования диапазон дат
		Date dt1, dt2;
		if (calc.getCalcTp() == 2) {
			// формирование по ОДН - задать последнюю дату
			dt1 = calc.getReqConfig().getCurDt2();
			dt2 = calc.getReqConfig().getCurDt2();
		} else {
			// прочее формирование
			dt1 = calc.getReqConfig().getCurDt1();
			dt2 = calc.getReqConfig().getCurDt2();
		}

		// найти все счетчики по Лиц.счету, по услуге
		for (c.setTime(dt1); !c.getTime().after(dt2); c.add(Calendar.DATE, 1)) {
			// calc.setGenDt(c.getTime());
			for (MLogs ml : metMng.getAllMetLogByServTp(rqn, kart, serv, null)) {
				metMng.delNodeVol(rqn, calc.getReqConfig().getChng()==null ? null : calc.getReqConfig().getChng().getId(),
						calc.getReqConfig().getChng(), ml, calc.getCalcTp(), calc.getReqConfig().getCurDt1(),
						calc.getReqConfig().getCurDt2());
			}

		}
	}

	/**
	 * Распределить объем по счетчикам лицевого
	 * 
	 * @param calcTp
	 *            - тип обработки
	 * @throws ErrorWhileDist
	 */
	private void distKartServTp(int rqn, Kart kart, Serv serv, Calc calc) throws ErrorWhileDist {
		// найти все начальные узлы расчета по лиц.счету и по услуге
		/*for (MLogs ml : metMng.getAllMetLogByServTp(rqn, kart, serv, null)) {
			 log.info("Услуга: serv.cd={}, Узел id={}", serv.getCd() , ml.getId());
		}*/
		
		for (MLogs ml : metMng.getAllMetLogByServTp(rqn, kart, serv, null)) {
			 //log.info("Услуга: serv.cd={}, Узел id={}", serv.getCd() , ml.getId());
			distGraph(ml, calc);
		}
	}

	/**
	 * распределить объем по дому
	 * 
	 * @param houseId
	 * @return
	 * @throws ErrorWhileDist
	 */
	@Async
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
	public Future<Result> distHouseVol(RequestConfig reqConfig, int houseId) throws ErrorWhileDist {
		Result res = new Result();
		res.setErr(0);
		res.setItemId(houseId);
		Future<Result> fut = new AsyncResult<Result>(res);
		int rqn = reqConfig.getRqn();
		/*try {
			log.info("ЗАДЕРЖКА houseId={}", houseId);
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		if (1==1) {
			return fut;
		}
*/		
		//this.calc = calc;
		
		House h = em.find(House.class, houseId);

		Calc calc = new Calc(reqConfig);
		// установить инициализацию дома
		// установить дом и счет
		calc.setHouse(h);
		log.info("УСТАНОВЛЕН house.id={}", h.getId());
		if (calc.getArea() == null) {
			throw new ErrorWhileDist(
					"Ошибка! По записи house.id=" + houseId + ", в его street, не заполнено поле area!");
		}

		// блокировка дома для распределения объемов
		int waitTick = 0;
		while (!config.lock.setLockDistHouse(rqn, houseId)) {
			waitTick++;
			if (waitTick > 60) {
				log.error("********ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!ВНИМАНИЕ!");
				log.error(
						"********НЕВОЗМОЖНО РАЗБЛОКИРОВАТЬ к houseId={} для распределения объемов в ТЕЧЕНИИ 100 сек!{}",
						houseId);
				throw new ErrorWhileDist("Ошибка при попытке блокировки дома house.id=" + houseId);
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
				throw new ErrorWhileDist("Ошибка при попытке блокировки дома house.id=" + houseId);
			}
		}

		try {
			distGen = ctx.getBean(DistGen.class);

			// найти все необходимые услуги для удаления объемов
			for (Serv s : servMng.getForDistVol()) {
				calc.setServ(s);
				try {
					delHouseVolServ(rqn, calc);
				} catch (CyclicMeter e) {
					e.printStackTrace();
					throw new ErrorWhileDist("Ошибка при распределении счетчиков в доме house.id=" + houseId);
				}
			}
	
			log.info("RQN={}, Распределение объемов по House.id={} house.klsk={}", calc.getReqConfig().getRqn(),
					calc.getHouse().getId(), calc.getHouse().getKlskId(), 2);
			// найти все необходимые услуги для распределения
			try {
				for (Serv s : servMng.getForDistVol()) {
					calc.setServ(s);
					log.info("RQN={}, Распределение услуги: cd={}", calc.getReqConfig().getRqn(), s.getCd(), 2);
					distHouseServ(rqn, calc);
				}
			} catch (ErrorWhileDist e) {
				e.printStackTrace();
				throw new ErrorWhileDist("Ошибка при распределении объемов по дому: house.id=" + houseId);
			}
		} finally {
			// разблокировать дом
			config.lock.unlockDistHouse(rqn, houseId);
		}
		
		return fut;
	}

	/**
	 * распределить объем по услуге данного дома
	 * 
	 * @throws ErrorWhileDist
	 */
	private void distHouseServ(int rqn, Calc calc) throws ErrorWhileDist {
		//log.trace("******************Услуга*************=" + calc.getServ().getCd());
		calc.setCalcTp(1);
		distHouseServTp(rqn, calc.getServ().getServMet(), calc);// Расчет площади, кол-во прожив
		calc.setCalcTp(0);
		distHouseServTp(rqn, calc.getServ().getServMet(), calc);// Распределение объема
		calc.setCalcTp(2);
		distHouseServTp(rqn, calc.getServ().getServMet(), calc);// Расчет ОДН
		calc.setCalcTp(3);
		distHouseServTp(rqn, calc.getServ().getServMet(), calc);// Расчет пропорц.площади
		if (calc.getServ().getServOdn() != null) {
			calc.setCalcTp(0);
			distHouseServTp(rqn, calc.getServ().getServOdn(), calc);// Суммировать счетчики ОДН
		}
		
	}

	/**
	 * Распределить объем по вводам дома
	 * 
	 * @param calcTp
	 *            - тип обработки
	 * @throws ErrorWhileDist
	 */
	private void distHouseServTp(int rqn, Serv serv, Calc calc) throws ErrorWhileDist {
		//log.trace("Распределение по типу:" + calc.getCalcTp());
		// найти все вводы по дому и по услуге
		for (MLogs ml : metMng.getAllMetLogByServTp(rqn, calc.getHouse(), serv, "Ввод")) {
			//log.trace("Вызов distGraph c id=" + ml.getId());
			distGraph(ml, calc);
		}
	}

	/**
	 * Распределить граф начиная с mLog
	 * 
	 * @param ml
	 *            - начальный узел распределения
	 * @throws ErrorWhileDist
	 */
	private void distGraph(MLogs ml, Calc calc) throws ErrorWhileDist {
		//log.trace("DistServ.distGraph: Распределение счетчика:" + ml.getId());
		// перебрать все необходимые даты, за период
		Calendar c = Calendar.getInstance();
		// необходимый для формирования диапазон дат
		Date dt1, dt2;
		if (calc.getCalcTp() == 2) {
			// формирование по ОДН - задать последнюю дату
			dt1 = calc.getReqConfig().getCurDt2();
			dt2 = calc.getReqConfig().getCurDt2();
		} else {
			// прочее формирование
			dt1 = calc.getReqConfig().getCurDt1();
			dt2 = calc.getReqConfig().getCurDt2();
		}
		for (c.setTime(dt1); !c.getTime().after(dt2); c.add(Calendar.DATE, 1)) {
			calc.setGenDt(c.getTime());
			@SuppressWarnings("unused")
			NodeVol dummy;
			try {
				dummy = distGen.distNode(calc, ml, calc.getCalcTp(), calc.getGenDt());
				// почистить коллекцию обработанных счетчиков
				distGen.clearLstChecks();

			} catch (WrongGetMethod e) {
				e.printStackTrace();
				throw new ErrorWhileDist("Dist.distGraph: При расчете счетчика MeterLog.Id=" + ml.getId()
						+ " , обнаружен замкнутый цикл");
			} catch (EmptyServ e) {
				e.printStackTrace();
				throw new ErrorWhileDist("Dist.distGraph: Пустая услуга при рекурсивном вызове BillServ.distNode()");
			} catch (EmptyStorable e) {
				e.printStackTrace();
				throw new ErrorWhileDist(
						"Dist.distGraph: Пустой хранимый компонент при рекурсивном вызове BillServ.distNode()");
			} catch (NotFoundODNLimit e) {
				e.printStackTrace();
				throw new ErrorWhileDist(
						"Dist.distGraph: Не найден лимит ОДН в счетчике ОДН, при вызове BillServ.distNode()");
			} catch (NotFoundNode e) {
				e.printStackTrace();
				throw new ErrorWhileDist("Dist.distGraph: Не найден нужный счетчик, при вызове BillServ.distNode(): ");
			} catch (EmptyPar e) {
				e.printStackTrace();
				throw new ErrorWhileDist(
						"Dist.distGraph: Не найден необходимый параметр объекта, при вызове BillServ.distNode(): ");
			} catch (WrongValue e) {
				e.printStackTrace();
				throw new ErrorWhileDist(
						"Dist.distGraph: Некорректное значение в расчете, при вызове BillServ.distNode(): ");
			}
		}

	}

	
	/**
	 * Автоначисление объемов по дому
	 * 
	 * @param houseId
	 * @return
	 */
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
	public void distHouseAutoVol(int houseId) {
		Date dt1 = config.getCurDt1();
		Date dt2 = config.getCurDt2();
		log.info("Автоначисление за период: dt1={}, dt2={}", dt1, dt2);
		House house = em.find(House.class, houseId);
		Calc calc = new Calc(null);
		calc.setHouse(house);
		
		// получить услуги для автоначисления
		List<Serv> lstServ = servDao.getServAutoVol();
		lstServ.stream().filter(t-> t.getId().equals(79))
			.forEach(t-> {
			log.info("Автоначисление по услуге {}", t.getName());
			try {
				distHouseServAutoVol(house, t, dt1, dt2);
			} catch (EmptyStorable e) {
				e.printStackTrace();
				log.info("ОШИБКА ВО ВРЕМЯ АВТОНАЧИСЛЕНИЯ по услуге {}", t.getName());
			}
		});
		
	}
	
	
	/**
	 * Автоначисление объемов по услуге и дому
	 * @param house - дом
	 * @param serv - услуга
	 * @throws EmptyStorable 
	 */
	private void distHouseServAutoVol(House house, Serv serv, Date dt1, Date dt2) throws EmptyStorable {
		log.info("Автоначисление по дому: house.id={}", house.getId());
		RequestConfig reqConfig = new RequestConfig();
		// String dist, String tp, Integer chngId, int rqn, Date genDt1, Date genDt2, Chng chng, Date curDt1, Date curDt2
		reqConfig.setUp("0", "0", null, 1, null, null, null, config.getCurDt1(), config.getCurDt2());

		// получить все счетчики ИПУ по дому и заданной услуге 
		// по которым не было показаний в данном периоде или понеисправным 
		// с учетом существования счетчика в METER_EXS?
		List<MeterDTO> lstMeterDTO = metMng.getAllMeterAutoVol(house, serv, dt1, dt2); //meterDao.getAllWoVolMeterByHouseServ(house, serv, dt1, dt2);
		
		List <MeterDTO> lst = new ArrayList<MeterDTO>(2);
		Kart kartOld = null;
		for (MeterDTO t: lstMeterDTO) {
			if (t.getMeter().getId().equals(90787)) {
				log.info("chk");
			}
			// получить текущий лиц.счет
			Kart kart = t.getMeter().getMeterLog().getKart();
			Integer lsk = kart.getLsk();
			if (kartOld!=null && !kartOld.getLsk().equals(lsk)) {
				// новый лиц.счет
				// обработать счетчики старого лиц.счета
				Calc calc = new Calc(reqConfig);
				// установить дом и счет
				calc.setHouse(kartOld.getKw().getHouse());
				calc.setKart(kartOld);

				log.info("Автоначисление по лиц.сч: kart.lsk={}", kartOld.getLsk());
				procMeter(calc, lst, dt1, dt2);
				lst.clear();
			}
			kartOld = kart;	
		   
			// добавить счетчик
			lst.add(t);			
		}
		// обработать счетчики (последнюю часть)
		if (lst.size()!=0) {
			Calc calc = new Calc(reqConfig);
			// установить дом и счет
			calc.setHouse(kartOld.getKw().getHouse());
			calc.setKart(kartOld);
			log.info("Автоначисление по лиц.сч: kart.lsk={}", kartOld.getLsk());
			procMeter(calc, lst, dt1, dt2);
		}
	}

	/**
	 * Обработать счетчики
	 * @param lst - список DTO Счетчиков, для обработкиs
	 * @param dt1 - начало периода
	 * @param dt2 - окончание периода
	 * @throws EmptyStorable 
	 */
	private void procMeter(Calc calc, List<MeterDTO> lst, Date dt1, Date dt2) throws EmptyStorable {
		Lst volTp = lstMng.getByCD("Фактический объем");

		// кол-во месяцев по законодательству
		int cntMonth = 3;
		for (MeterDTO t: lst) {
			String str = null;
			
			str = getStatus(t, str);
			log.info("Автоначисление по счетчику: Meter.id={}, Статус: {}", t.getMeter().getId(), str);
			AvgVol avgVol = null;
			if (t.getTp().equals(0D)) {
				// получить средний объем за период последних N мес при непередаче показаний
				avgVol = new AvgVol();
				avgVol.vol = metMng.getAvgVol(t.getMeter(), cntMonth, dt2);
				log.info("Получен средний объем за период последних N мес при непередаче показаний, объем={}", avgVol.vol);
			} else {
				// получить средний объем и кол-во месяцев за период N мес. до последней передачи объема, при неисправном счетчике
				avgVol = metMng.getAvgVolBeforeLastSend(t.getMeter(), cntMonth, dt2);
				log.info("Получен средний объем и кол-во месяцев за период N мес. до последней передачи объема, при неисправном(и.т.п.) счетчике, объем={}, кол-во мес.={}", avgVol.vol, avgVol.cnt);
			}
			
			if (Utl.nvl(avgVol.vol, 0D) != 0D && avgVol.cnt <= cntMonth) {
				BigDecimal vl = new BigDecimal(avgVol.vol);
			    vl = vl.setScale(6, RoundingMode.HALF_UP);

			    // меньше N месяцев - начислить объем по среднему
				log.info("Автоначисление по среднему объему, по счетчику: Meter.id={}, vol={}", t.getMeter().getId(), vl);
				Vol vol = Vol.builder()
						.withMet(t.getMeter())
						.withTp(volTp)
						.withVol1(vl.doubleValue())
						.withDt1(dt1)
						.withDt2(dt2)
						.withStatus(0)
						.build();
				em.persist(vol);
			} else {
				// больше N месяцев или нулевой последний объем - начислить объем по нормативу потребления / кол-во счетчиков в лиц.счете
				// если был не допуск к счетчику - с применением повыш коэфф
				Serv serv = t.getMeter().getMeterLog().getServ();
				// получить норматив
				Standart stdt = kartMng.getStandartVol(1, calc, serv, dt2, 0);
				BigDecimal vl;
				if (stdt.partVol != 0D) {
					log.info("Получен суммарный норматив на всех проживающих, разделить на кол-во счетчиков, норматив={}, кол во счетчиков={}", stdt.partVol, lst.size());
					// получить суммарный норматив на всех проживающих, разделить на кол-во счетчиков
					vl = new BigDecimal(stdt.partVol / lst.size());
				} else {
					log.info("Кол-во проживающих = 0, взять норматив на 1 человека, разделить на кол-во счетчиков, норматив={}, кол во счетчиков={}", stdt.vol / lst.size());
					// если кол-во проживающих = 0, взять норматив на 1 человека, разделить на кол-во счетчиков
					vl = new BigDecimal(stdt.vol / lst.size());
				}
			    
				vl = vl.setScale(6, RoundingMode.HALF_UP);
			    
				log.info("Автоначисление по нормативу потребления, по счетчику: Meter.id={}, vol={}", t.getMeter().getId(), vl);
				Vol vol = Vol.builder()
						.withMet(t.getMeter())
						.withTp(volTp)
						.withVol1(vl.doubleValue())
						.withDt1(dt1)
						.withDt2(dt2)
						.withStatus(0)
						.build();
				em.persist(vol);
			}
		}
	}

	/**
	 * Получить строку статуса счетчика
	 * @param t - DTO счетчика
	 * @param str - строка статуса
	 * @return
	 */
	private String getStatus(MeterDTO t, String str) {
		switch (t.getTp().intValue()) {
		case 0 : {
			str = "Непредоставление показаний";
			break;
			}
		case 2 : {
			str = "Неисправный";
			break;
			}
		case 3 : {
			str = "Не прошел проверку";
			break;
			}
		case 4 : {
			str = "Отказ допуска";
			break;
			}
		}
		return str;
	}

	/**
	 * ТЕСТ-вызов не удалять!
	 * 
	 * @param id
	 * @return
	 */
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	public void distTest(Integer id) {
		log.info("====== distTest");
		distTest2(id);
	}

	/**
	 * ТЕСТ-вызов не удалять!
	 * 
	 * @param id
	 * @return
	 */
	public void distTest2(Integer id) {
		log.info("====== distTest2");

		MLogs mLog = em.find(MeterLog.class, 520459);
		for (Iterator<Vol> iterator = mLog.getVol().iterator(); iterator.hasNext();) {
			Vol vol = iterator.next();
			iterator.remove();
		}

		Date dd1 = Utl.getDateFromStr("01.10.2017");
		Date dd2 = Utl.getDateFromStr("31.10.2017");
		Lst volTp = lstMng.getByCD("Лимит ОДН");
		
//		Vol vol = new Vol((MeterLog) mLog, volTp, Double.valueOf(id), null, dt1, dt2, null, null);
//		mLog.getVol().add(vol);
		mLog.getVol().stream()
				.filter(t -> t.getDt1().getTime() >= dd1.getTime() && t.getDt1().getTime() <= dd2.getTime())
				.forEach(t -> {
					log.info("id={} БЫЛО ЗАПИСАНО dt={}, vol={}", id, t.getDt1(), t.getVol1());
				});

		log.info("Записан id={}", id);
		try {
			Thread.sleep(id);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
