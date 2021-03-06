package HBaseIA.GIS;

import java.io.IOException;
import java.util.Comparator;
import java.util.Queue;

import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.PrefixFilter;

import HBaseIA.GIS.model.DistanceComparator;
import HBaseIA.GIS.model.QueryMatch;
import ch.hsr.geohash.GeoHash;

import com.google.common.collect.MinMaxPriorityQueue;
/**
 * 输入一个矢量数据，查询离这个热点最近的n个点，实现最近邻查询
 * @author 大象
 *
 */
public class KNNQuery {

	static final byte[] TABLE = "geo".getBytes();
	static final byte[] FAMILY = "a".getBytes();
	static final byte[] ID = "id".getBytes();
	static final byte[] X_COL = "lon".getBytes();
	static final byte[] Y_COL = "lat".getBytes();


	final HTablePool pool;
	int precision = 7;

	public KNNQuery(HTablePool pool) {
		this.pool = pool;
	}

	public KNNQuery(HTablePool pool, int characterPrecision) {
		this.pool = pool;
		this.precision = characterPrecision;
	}

	Queue<QueryMatch> takeN(Comparator<QueryMatch> comp, String prefix, int n)
			throws IOException {
		Queue<QueryMatch> candidates = MinMaxPriorityQueue.orderedBy(comp)
				.maximumSize(n).create();

		Scan scan = new Scan(prefix.getBytes());
		scan.setFilter(new PrefixFilter(prefix.getBytes()));
		scan.addFamily(FAMILY);
		scan.setMaxVersions(1);
		scan.setCaching(50);//把扫描器每次RPC调用时返回的记录数设置为50

		HTableInterface table = pool.getTable(TABLE);

		int cnt = 0;
		ResultScanner scanner = table.getScanner(scan);
		for (Result r : scanner) {
			String hash = new String(r.getRow());
			String id = new String(r.getValue(FAMILY, ID));
			String lon = new String(r.getValue(FAMILY, X_COL));
			String lat = new String(r.getValue(FAMILY, Y_COL));
			candidates.add(new QueryMatch(id, hash, Double.parseDouble(lon),
					Double.parseDouble(lat)));
			cnt++;
		}

		table.close();

		System.out.println(String.format(
				"扫描前缀字符串 '%s' 后返回   %s 个候选热点.", prefix, cnt));
		return candidates;
	}

	public Queue<QueryMatch> queryKNN(double lat, double lon, int n)
			throws IOException {
		DistanceComparator comp = new DistanceComparator(lon, lat);
		Queue<QueryMatch> ret = MinMaxPriorityQueue.orderedBy(comp)
				.maximumSize(n).create();

		GeoHash target = GeoHash.withCharacterPrecision(lat, lon, precision);
		ret.addAll(takeN(comp, target.toBase32(), n));
		for (GeoHash h : target.getAdjacent()) {
			ret.addAll(takeN(comp, h.toBase32(), n));
		}

		return ret;
	}

	public static void main(String[] args) throws IOException {

		if (args.length != 3) {
			System.out.println("输入参数不精确");
			System.exit(0);
		}

		double lon = Double.parseDouble(args[0]);
		double lat = Double.parseDouble(args[1]);
		int n = Integer.parseInt(args[2]);

		HTablePool pool = new HTablePool();
		KNNQuery q = new KNNQuery(pool);
		Queue<QueryMatch> ret = q.queryKNN(lat, lon, n);

		QueryMatch m;
		while ((m = ret.poll()) != null) {
			System.out.println(m);
		}

		pool.close();
	}
}
