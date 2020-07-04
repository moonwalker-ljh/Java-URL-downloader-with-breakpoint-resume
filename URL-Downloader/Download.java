import java.io.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.net.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

//下载线程
class ThrDownload extends Thread {
    private int no; // 线程号
    private RandomAccessFile out;
    private InputStream in; // 输入字节流
    private URL url; // URL
    private final int start; // 该下载线程下载的文件起始和结束位置
    private int end;
    private byte[] b; // 读写缓冲区
    private int len; // 该下载线程开始下载到现在下载的字节数
    private int finish; // 该下载线程总下载字节数

    // 下载线程共享资源
    public static int file_len = 0; // 需要下载的文件总长度
    public static int buf_len = 8192; // 缓冲区大小:1MB
    public static int num_thread = 1; // 下载线程数
    public static String url_name; // 下载文件的url名
    public static String save_name; // 保存文件名
    public static ThrDownload[] thr_download; // 下载线程
    public static ThrCount thr_count; // 统计线程
    public static boolean isPart = false; // 当前需下载的文件是否已经下载过一部分，默认为否
    public static int[] start_pos; // 从临时文件读取的各下载线程开始位置

    // 构造函数
    public ThrDownload(final int no) {
        final int block_len = file_len / num_thread; // 计算每个下载线程需要下载的数据长度
        if (!isPart) // 第一次下载
            start = block_len * (no - 1); // 该线程下载的数据开始位置
        else { // 不是第一次下载
            start = block_len * (no - 1) + start_pos[no - 1];
        }
        end = (block_len * no) - 1; // 该线程下载的数据结束位置
        if (no == num_thread)
            end = file_len - 1;
        len = 0; // 当前已下载的字节数初始化
        finish = start - block_len * (no - 1); // 当前总共下载的字节数
        try {
            url = new URL(url_name);
            final HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestProperty("Range", "byte=" + start + "-" + end);
            in = con.getInputStream();
            if (con.getResponseCode() >= 300)
                new Exception("Http响应问题：" + con.getResponseCode());
            out = new RandomAccessFile(save_name, "rw");
            this.no = no;
            out.seek(start); // 在保存文件中确定保存的位置
            b = new byte[buf_len]; // 初始化缓冲区
        } catch (final Exception e) {
            System.out.println(e.toString());
        }
    }

    // 所有下载线程的初始化函数
    public static boolean init(final String url_name, final String save_name, final int num_thread) {
        ThrDownload.url_name = url_name;
        ThrDownload.save_name = save_name;
        ThrDownload.num_thread = num_thread;
        try {
            final URL url = new URL(url_name);
            final HttpURLConnection con = (HttpURLConnection) url.openConnection();
            ThrDownload.file_len = con.getContentLength();
        } catch (final Exception e) {
            return false;
        }
        if (file_len == -1)
            return false; // 为-1说明资源分块传输无Conten_length

        thr_download = new ThrDownload[num_thread];
        for (int i = 0; i < num_thread; i++) {
            thr_download[i] = new ThrDownload(i + 1); // 下载线程的初始化
        }
        thr_count = new ThrCount(); // 统计线程的初始化
        return true;
    }

    // 下载线程体
    public void run() {
        int L; // 读取出的字节数，为-1的话已经读取到文件末尾
        try {
            while (true) {
                L = in.read(b); // 读取直接到缓冲区
                if (L == -1)
                    break;
                out.write(b, 0, L); // 将缓冲区写到保存文件
                len += L;
                finish += L;
            }
            in.close();
            out.close();
        } catch (final Exception e) {
        }
    }

    // 查询开始下载到现在当前已经下载的数据长度
    public int getLen() {
        return len;
    }

    // 查询当前已经保存保存的数据长度
    public int getFinish() {
        return finish;
    }
}

// 统计线程
class ThrCount extends Thread {
    public static double begin_time; // 下载开始时间
    public static double current_time; // 当前时间

    public void run() {
        while (true) {
            try {
                TimeUnit.SECONDS.sleep(1);// 每隔一秒输出一次进度
            } catch (final InterruptedException ex) {
            }
            current_time = (new Date()).getTime() / 1000.0;
            double percent = 0;
            double speed = 0; // 计算下载百分比和平均下载速度
            for (int i = 0; i < ThrDownload.num_thread; i++) {
                percent += ThrDownload.thr_download[i].getFinish();
                speed += ThrDownload.thr_download[i].getLen();
            }

            // 输出当前进度等
            DecimalFormat[] df = new DecimalFormat[4]; // 将double格式化
            for (int i = 0; i < 4; i++)
                df[i] = new DecimalFormat("###.0");
            Download.tf1.setText("已下载文件大小：" + df[0].format(percent / 1000) + "KB / "
                    + df[1].format(ThrDownload.file_len / 1000) + "KB");
            speed = speed / 1000.0 / (current_time - begin_time); // 单位KB/S
            percent /= ThrDownload.file_len;
            Download.tf2.setText(df[2].format(percent * 100) + "%已下载, 平均下载速度=" + df[3].format(speed) + "KB/S");
            Download.pb.setValue((int) (percent * 100)); // 更新进度条的进度
            Download.pb.repaint(); // 手动重新绘制进度条

            // 判断是否所有下载线程都已经结束，若否则继续下载
            int i;
            for (i = 0; i < ThrDownload.num_thread; i++)
                if (ThrDownload.thr_download[i].isAlive())
                    break;
            if (i >= ThrDownload.num_thread) // 全部线程下载完成
            {
                for (i = 0; i < ThrDownload.num_thread; i++)
                    ThrDownload.thr_download[i].stop();
                try {
                    File destroy = new File(ThrDownload.save_name + ".cfg");
                    if (destroy.exists()) // 存在配置文件
                        destroy.delete();
                } catch (Exception e) {
                    System.out.println(e.toString());
                }
                break;
            }
        }
    }
}

// 输入参数对话框
class MyDialog extends JDialog implements ActionListener {
    JTextField input_url, input_num, input_pathname;
    JButton button_down, button_choose; // 确定按钮
    FileDialog save; // 选择保存文件的位置
    String url = "", pathname = "";
    int num; // 下载线程数

    MyDialog(final JFrame f, final String s) { // 传入父容器和对话框标题
        super(f, s);
        setLayout(new FlowLayout(FlowLayout.LEFT, 50, 20));
        setBounds(600, 260, 500, 400); // 设置对话框位置和大小
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        // 添加输入url的组件
        final JLabel label1 = new JLabel("输入URL：");
        label1.setFont(new Font("宋体", Font.BOLD, 15));
        add(label1);
        input_url = new JTextField(35);
        input_url.setFont(new Font("宋体", Font.BOLD, 20));
        input_url.setText("https://qd.myapp.com/myapp/qqteam/pcqq/PCQQ2019.exe");
        add(input_url);

        // 添加输入下载线程数的组件
        final JLabel label2 = new JLabel("输入下载线程数：");
        label2.setFont(new Font("宋体", Font.BOLD, 15));
        add(label2);
        input_num = new JTextField(35);
        input_num.setFont(new Font("宋体", Font.BOLD, 20));
        input_num.setText("5");
        add(input_num);

        // 添加输入保存文件路径名的组件
        final JLabel label3 = new JLabel("输入保存文件路径名：");
        label3.setFont(new Font("宋体", Font.BOLD, 15));
        add(label3);
        input_pathname = new JTextField(30);
        input_pathname.setFont(new Font("宋体", Font.BOLD, 20));
        input_pathname.setText("");
        add(input_pathname);
        // 文件对话框初始化
        save = new FileDialog(this, "选择保存位置", FileDialog.SAVE);
        button_choose = new JButton("...");
        button_choose.addActionListener(this);
        button_choose.setPreferredSize(new Dimension(20, 30));
        add(button_choose);

        // 添加确定按钮
        button_down = new JButton("确定");
        button_down.addActionListener(this);
        button_down.setFont(new Font("宋体", Font.BOLD, 20));
        button_down.setPreferredSize(new Dimension(100, 50));
        add(button_down);
    }

    public void actionPerformed(final ActionEvent e) {
        switch (e.getActionCommand()) {
        case "...":
            save.setVisible(true);
            input_pathname.setText(save.getDirectory() + save.getFile());
            break;
        case "确定":
            try {
                url = input_url.getText();
                URL test = new URL(url);
            } catch (MalformedURLException ex) { // 检查URL合法性
                JOptionPane.showMessageDialog(this, "URL格式错误！", "错误", JOptionPane.ERROR_MESSAGE);
                url = "";
                return;
            }
            pathname = input_pathname.getText();
            num = Integer.parseInt(input_num.getText());
            setVisible(false);
            break;
        default:
            break;
        }

    }

    public String get_url() {
        return url;
    }

    public int get_num() {
        return num;
    }

    public String get_pathname() {
        return pathname;
    }
}

public class Download {
    private static JFrame f;
    public static JProgressBar pb; // 进度条
    public static JTextField tf1, tf2; // 编辑框组件
    private JButton[] bt; // 按钮组件
    FileDialog con_download; // 继续下载时打开配置文件的对话框
    private MyDialog dialog;

    class event_window extends WindowAdapter { // 窗口关闭事件监听
        public void windowClosing(final WindowEvent e) {
            System.exit(0);
        }
    }

    class event_action implements ActionListener { // 按钮点击事件监听
        public void actionPerformed(final ActionEvent e) {
            final String s = e.getActionCommand();
            switch (s) {
            case "新建下载":
                dialog.setVisible(true);
                ThrDownload.isPart = false;
                if (!ThrDownload.init(dialog.get_url(), dialog.get_pathname(), dialog.get_num()))
                    tf1.setText("下载线程初始化失败！");
                if (dialog.get_url() != "")
                    tf1.setText("URL:" + dialog.get_url());
                if (dialog.get_pathname() != "")
                    tf2.setText("下载文件保存到" + dialog.get_pathname());
                break;
            case "继续下载":
                con_download.setVisible(true);
                // 读取配置文件
                String url="",save_name="";
                int n=0;
                try {
                    ThrDownload.isPart = true;
                    FileReader propFile = new FileReader(con_download.getDirectory()+con_download.getFile());
                    Properties prop = new Properties();
                    prop.load(propFile);
                    url = prop.getProperty("URL"); // 载入URL
                    save_name = prop.getProperty("Dir"); // 载入路径
                    n = Integer.parseInt(prop.getProperty("Thr")); // 载入线程数
                    ThrDownload.start_pos = new int[n];
                    String progress = prop.getProperty("Prog"); // 解析每个线程的进度
                    String[] strProg = progress.split("@");
                    if (strProg.length != n)
                        throw new Exception("Error.");
                    for (int i = 0; i < n; i++) 
                        ThrDownload.start_pos[i] = Integer.parseInt(strProg[i]);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                if (!ThrDownload.init(url, save_name, n))
                    tf1.setText("下载线程初始化失败！");
                if (url != "")
                    tf1.setText("URL:" + url);
                tf2.setText("继续下载:" + save_name);
                break;
            case "开始":
                ThrCount.begin_time = (new Date()).getTime() / 1000.0;
                for (int i = 0; i < ThrDownload.num_thread; i++)
                    ThrDownload.thr_download[i].start(); // 所有线程开始下载
                ThrDownload.thr_count.start(); // 开始统计
                break;
            case "暂停": {
                tf2.setText("已暂停下载...");
                int i;
                for (i = 0; i < ThrDownload.num_thread; i++)
                    if (ThrDownload.thr_download[i].isAlive())
                        break;
                if (i != ThrDownload.num_thread) { // 退出时还有线程没有下载完成，保存配置文件
                    try {
                        Properties prop = new Properties();
                        prop.setProperty("URL", ThrDownload.url_name);
                        prop.setProperty("Dir", ThrDownload.save_name);
                        prop.setProperty("Thr", Integer.toString(ThrDownload.num_thread));
                        String temp = Integer.toString(ThrDownload.thr_download[0].getLen());
                        for (int j = 1; j < ThrDownload.num_thread; j++) {
                            temp = temp + "@" + Integer.toString(ThrDownload.thr_download[j].getFinish());
                        }
                        prop.setProperty("Prog", temp);
                        File propFile = new File(ThrDownload.save_name + ".cfg");
                        prop.store(new FileWriter(propFile), "");
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                for (i = 0; i < ThrDownload.num_thread; i++)
                    ThrDownload.thr_download[i].stop();
            }
                ThrDownload.thr_count.stop();
                break;
            default:
                break;
            }
        }
    }

    public void init() { // 图形界面初始化
        // 容器和布局定义
        f = new JFrame("多线程下载器");
        f.setLayout(null);

        // 进度条初始化
        pb = new JProgressBar(0, 100);
        // 设置进度最小最大值
        pb.setValue(0); // 当前值
        pb.setStringPainted(true);// 绘制百分比文本（进度条中间显示的百分数）
        pb.setIndeterminate(false);
        pb.setPreferredSize(new Dimension(510, 20));
        f.add(pb);
        pb.setBounds(50, 10, 500, 30);

        // 对话框初始化
        dialog = new MyDialog(f, "输入下载参数");
        dialog.setModal(true);

        //文件对话框的初始化
        con_download = new FileDialog(f,"选择继续下载的配置文件",FileDialog.LOAD);

        // 显示框初始化
        tf1 = new JTextField(56);
        tf1.setFont(new Font("宋体", Font.BOLD, 15));
        tf1.setText("URL");
        f.add(tf1);
        tf1.setBounds(50, 50, 500, 30);
        tf2 = new JTextField(56);
        tf2.setFont(new Font("宋体", Font.BOLD, 15));
        tf2.setText("保存路径");
        f.add(tf2);
        tf2.setBounds(50, 90, 500, 30);

        // 按钮初始化
        bt = new JButton[4];
        bt[0] = new JButton("新建下载");
        bt[1] = new JButton("继续下载");
        bt[2] = new JButton("开始");
        bt[3] = new JButton("暂停");
        for (int i = 0; i < 4; i++) // 设置按钮字体样式
            bt[i].setFont(new Font("宋体", Font.BOLD, 17));
        for (int i = 0; i < 4; i++)
            f.add(bt[i]);
        bt[0].setBounds(50, 130, 110, 30);
        bt[1].setBounds(180, 130, 110, 30);
        bt[2].setBounds(310, 130, 110, 30);
        bt[3].setBounds(430, 130, 110, 30);

        // 添加事件监听
        final event_window e_w = new event_window();
        final event_action e_c = new event_action();
        f.addWindowListener(e_w);
        for (int i = 0; i < 4; i++) {
            bt[i].addActionListener(e_c);
        }
    }

    public void display() { // 显示窗口
        f.setSize(600, 230);
        f.setLocation(700, 350);
        f.setVisible(true);
        f.setResizable(false);
    }

    public static void main(final String args[]) {
        final Download a = new Download();
        a.init();
        a.display();
    }
}